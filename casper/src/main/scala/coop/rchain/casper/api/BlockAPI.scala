package coop.rchain.casper.api

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import cats.{Applicative, Monad}
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.blockStore.BlockStore
import coop.rchain.blockstorage.dag.BlockDagStorage
import coop.rchain.blockstorage.dag.BlockDagStorage.DeployId
import coop.rchain.casper.DeployError._
import coop.rchain.casper._
import coop.rchain.casper.blocks.proposer.ProposeResult._
import coop.rchain.casper.blocks.proposer._
import coop.rchain.casper.engine.EngineCell._
import coop.rchain.casper.engine._
import coop.rchain.casper.genesis.contracts.StandardDeploys
import coop.rchain.casper.protocol._
import coop.rchain.casper.state.instances.ProposerState
import coop.rchain.casper.syntax._
import coop.rchain.casper.util._
import coop.rchain.casper.util.rholang.{RuntimeManager, Tools}
import coop.rchain.crypto.PublicKey
import coop.rchain.crypto.signatures.Signed
import coop.rchain.graphz._
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.rholang.sorter.Sortable._
import coop.rchain.models.serialization.implicits.mkProtobufInstance
import coop.rchain.models.syntax._
import coop.rchain.models.{BlockMetadata, Par}
import coop.rchain.rspace.hashing.StableHashProvider
import coop.rchain.rspace.trace._
import coop.rchain.shared.Log
import coop.rchain.shared.syntax._
import fs2.Stream

import scala.collection.immutable.SortedMap

object BlockAPI {
  type Error     = String
  type ApiErr[A] = Either[Error, A]

  val BlockAPIMetricsSource: Metrics.Source = Metrics.Source(Metrics.BaseSource, "block-api")
  val DeploySource: Metrics.Source          = Metrics.Source(BlockAPIMetricsSource, "deploy")
  val GetBlockSource: Metrics.Source        = Metrics.Source(BlockAPIMetricsSource, "get-block")

  val reportTransformer = new ReportingProtoTransformer()

  // TODO: we should refactor BlockApi with applicative errors for better classification
  //  of errors and to overcome nesting when validating data.
  final case class BlockRetrievalError(message: String) extends Exception

  def deploy[F[_]: Concurrent: EngineCell: Log: Span](
      d: Signed[DeployData],
      triggerPropose: Option[ProposeFunction[F]],
      minPhloPrice: Long,
      isNodeReadOnly: Boolean,
      shardId: String
  ): F[ApiErr[String]] = Span[F].trace(DeploySource) {

    def casperDeploy(casper: MultiParentCasper[F]): F[ApiErr[String]] =
      for {
        r <- casper
              .deploy(d)
              .map(
                _.bimap(
                  err => err.show,
                  res => s"Success!\nDeployId is: ${PrettyPrinter.buildStringNoLimit(res)}"
                )
              )
        // call a propose if proposer defined
        _ <- triggerPropose.traverse(_(casper, true))
      } yield r

    // Check if node is read-only
    val readOnlyError = new RuntimeException(
      "Deploy was rejected because node is running in read-only mode."
    ).raiseError[F, ApiErr[String]]
    val readOnlyCheck = readOnlyError.whenA(isNodeReadOnly)

    // Check if deploy's shardId equals to node shardId
    val shardIdError = new RuntimeException(
      s"Deploy shardId '${d.data.shardId}' is not as expected network shard '$shardId'."
    ).raiseError[F, ApiErr[String]]
    val shardIdCheck = shardIdError.whenA(d.data.shardId != shardId)

    // Check if deploy is signed with system keys
    val isForbiddenKey = StandardDeploys.systemPublicKeys.contains(d.pk)
    val forbiddenKeyError = new RuntimeException(
      s"Deploy refused because it's signed with forbidden private key."
    ).raiseError[F, ApiErr[String]]
    val forbiddenKeyCheck = forbiddenKeyError.whenA(isForbiddenKey)

    // Check if deploy has minimum phlo price
    val minPriceError = new RuntimeException(
      s"Phlo price ${d.data.phloPrice} is less than minimum price $minPhloPrice."
    ).raiseError[F, ApiErr[String]]
    val minPhloPriceCheck = minPriceError.whenA(d.data.phloPrice < minPhloPrice)

    // Error message in case Casper is not ready
    val errorMessage = "Could not deploy, casper instance was not available yet."
    val logErrorMessage = Log[F]
      .warn(errorMessage)
      .as(s"Error: $errorMessage".asLeft[String])

    readOnlyCheck >> shardIdCheck >> forbiddenKeyCheck >> minPhloPriceCheck >> EngineCell[F].read >>= (_.withCasper[
      ApiErr[String]
    ](
      casperDeploy,
      logErrorMessage
    ))
  }

  def createBlock[F[_]: Concurrent: EngineCell: Log](
      triggerProposeF: ProposeFunction[F],
      isAsync: Boolean = false
  ): F[ApiErr[String]] = {
    def logDebug(err: String)  = Log[F].debug(err) >> err.asLeft[String].pure[F]
    def logSucess(msg: String) = Log[F].info(msg) >> msg.asRight[Error].pure[F]
    def logWarn(msg: String)   = Log[F].warn(msg) >> msg.asLeft[String].pure[F]
    EngineCell[F].read >>= (
      _.withCasper[ApiErr[String]](
        casper =>
          for {
            // Trigger propose
            proposerResult <- triggerProposeF(casper, isAsync)
            r <- proposerResult match {
                  case ProposerEmpty =>
                    logDebug(s"Failure: another propose is in progress")
                  case ProposerFailure(status, seqNumber) =>
                    logDebug(s"Failure: $status (seqNum $seqNumber)")
                  case ProposerStarted(seqNumber) =>
                    logSucess(s"Propose started (seqNum $seqNumber)")
                  case ProposerSuccess(_, block) =>
                    // TODO: [WARNING] Format of this message is hardcoded in pyrchain when checking response result
                    //  Fix to use structured result with transport errors/codes.
                    // https://github.com/rchain/pyrchain/blob/a2959c75bf/rchain/client.py#L42
                    val blockHashHex = block.blockHash.toHexString
                    logSucess(s"Success! Block $blockHashHex created and added.")
                }
          } yield r,
        default = logWarn("Failure: casper instance is not available.")
      )
    )
  }

  // Get result of the propose
  def getProposeResult[F[_]: Concurrent: Log](
      proposerState: Ref[F, ProposerState[F]]
  ): F[ApiErr[String]] =
    for {
      pr <- proposerState.get.map(_.currProposeResult)
      r <- pr match {
            // return latest propose result
            case None =>
              for {
                result <- proposerState.get.map(
                           _.latestProposeResult.getOrElse(ProposeResult.notEnoughBlocks, None)
                         )
                msg = result._2 match {
                  case Some(block) =>
                    s"Success! Block ${block.blockHash.toHexString} created and added."
                      .asRight[Error]
                  case None => s"${result._1.proposeStatus.show}".asLeft[String]
                }
              } yield msg
            // wait for current propose to finish and return result
            case Some(resultDef) =>
              for {
                // this will hang API call until propose is complete, and then return result
                // TODO cancel this get when connection drops
                result <- resultDef.get
                msg = result._2 match {
                  case Some(block) =>
                    s"Success! Block ${block.blockHash.toHexString} created and added."
                      .asRight[Error]
                  case None => s"${result._1.proposeStatus.show}".asLeft[String]
                }
              } yield msg
          }
    } yield r

  /**
    * Performs transformation on a stream of blocks (from highest height, same heights loaded as batch)
    *
    * @param heightMap height map to iterate block hashes
    * @param transform function to run for each block
    * @return stream of transform results
    */
  private def getFromBlocks[F[_]: Sync: BlockStore, A](
      heightMap: SortedMap[Long, Set[BlockHash]]
  )(transform: BlockMessage => F[A]): Stream[F, List[A]] = {
    // TODO: check if conversion of SortedMap#toIndexedSeq is performant enough
    val reverseHeightMap = heightMap.toIndexedSeq.reverse
    val iterBlockHashes  = reverseHeightMap.iterator.map(_._2.toList)
    Stream
      .fromIterator(iterBlockHashes)
      .evalMap(_.traverse(BlockStore[F].getUnsafe))
      .evalMap(_.traverse(transform))
  }

  def getListeningNameDataResponse[F[_]: Concurrent: EngineCell: Log: BlockStore](
      depth: Int,
      listeningName: Par,
      maxBlocksLimit: Int
  ): F[ApiErr[(Seq[DataWithBlockInfo], Int)]] = {

    val errorMessage = "Could not get listening name data, casper instance was not available yet."

    def casperResponse(
        implicit casper: MultiParentCasper[F]
    ): F[ApiErr[(Seq[DataWithBlockInfo], Int)]] =
      for {
        heightMap           <- casper.blockDag.flatMap(_.getHeightMap)
        depthWithLimit      = Math.min(depth, maxBlocksLimit).toLong
        runtimeManager      <- casper.getRuntimeManager
        sortedListeningName <- parSortable.sortMatch[F](listeningName).map(_.term)
        blockDataStream = getFromBlocks(heightMap) { block =>
          getDataWithBlockInfo[F](runtimeManager, sortedListeningName, block)
        }
        // For compatibility with v0.12.x depth must include all blocks with the same height
        //  e.g. depth=1 should always return latest block created by the node
        blocksWithActiveName <- blockDataStream
                                 .map(_.flatten)
                                 .take(depthWithLimit)
                                 .compile
                                 .toList
                                 .map(_.flatten)
      } yield (blocksWithActiveName, blocksWithActiveName.length).asRight

    if (depth > maxBlocksLimit)
      s"Your request on getListeningName depth ${depth} exceed the max limit ${maxBlocksLimit}"
        .asLeft[(Seq[DataWithBlockInfo], Int)]
        .pure[F]
    else
      EngineCell[F].read >>= (_.withCasper[ApiErr[(Seq[DataWithBlockInfo], Int)]](
        casperResponse(_),
        Log[F]
          .warn(errorMessage)
          .as(s"Error: $errorMessage".asLeft)
      ))
  }

  def getListeningNameContinuationResponse[F[_]: Concurrent: EngineCell: Log: BlockStore](
      depth: Int,
      listeningNames: Seq[Par],
      maxBlocksLimit: Int
  ): F[ApiErr[(Seq[ContinuationsWithBlockInfo], Int)]] = {
    val errorMessage =
      "Could not get listening names continuation, casper instance was not available yet."
    def casperResponse(
        implicit casper: MultiParentCasper[F]
    ): F[ApiErr[(Seq[ContinuationsWithBlockInfo], Int)]] =
      for {
        heightMap      <- casper.blockDag.flatMap(_.getHeightMap)
        depthWithLimit = Math.min(depth, maxBlocksLimit).toLong
        runtimeManager <- casper.getRuntimeManager
        sortedListeningNames <- listeningNames.toList
                                 .traverse(parSortable.sortMatch[F](_).map(_.term))
        blockDataStream = getFromBlocks(heightMap) { block =>
          getContinuationsWithBlockInfo[F](runtimeManager, sortedListeningNames, block)
        }
        // For compatibility with v0.12.x depth must include all blocks with the same height
        //  e.g. depth=1 should always return latest block created by the node
        blocksWithActiveName <- blockDataStream
                                 .map(_.flatten)
                                 .take(depthWithLimit)
                                 .compile
                                 .toList
                                 .map(_.flatten)
      } yield (blocksWithActiveName, blocksWithActiveName.length).asRight

    if (depth > maxBlocksLimit)
      s"Your request on getListeningNameContinuation depth ${depth} exceed the max limit ${maxBlocksLimit}"
        .asLeft[(Seq[ContinuationsWithBlockInfo], Int)]
        .pure[F]
    else
      EngineCell[F].read >>= (_.withCasper[ApiErr[(Seq[ContinuationsWithBlockInfo], Int)]](
        casperResponse(_),
        Log[F]
          .warn(errorMessage)
          .as(s"Error: $errorMessage".asLeft)
      ))
  }

  private def getDataWithBlockInfo[F[_]: Log: BlockStore: Concurrent](
      runtimeManager: RuntimeManager[F],
      sortedListeningName: Par,
      block: BlockMessage
  ): F[Option[DataWithBlockInfo]] =
    // TODO: For Produce it doesn't make sense to have multiple names
    if (isListeningNameReduced(block, Seq(sortedListeningName))) {
      val stateHash = ProtoUtil.postStateHash(block)
      for {
        data      <- runtimeManager.getData(stateHash)(sortedListeningName)
        blockInfo <- getLightBlockInfo[F](block)
      } yield Option[DataWithBlockInfo](DataWithBlockInfo(data, blockInfo))
    } else {
      none[DataWithBlockInfo].pure[F]
    }

  private def getContinuationsWithBlockInfo[F[_]: Log: BlockStore: Concurrent](
      runtimeManager: RuntimeManager[F],
      sortedListeningNames: Seq[Par],
      block: BlockMessage
  ): F[Option[ContinuationsWithBlockInfo]] =
    if (isListeningNameReduced(block, sortedListeningNames)) {
      val stateHash = ProtoUtil.postStateHash(block)
      for {
        continuations <- runtimeManager.getContinuation(stateHash)(sortedListeningNames)
        continuationInfos = continuations.map(
          continuation => WaitingContinuationInfo(continuation._1, continuation._2)
        )
        blockInfo <- getLightBlockInfo[F](block)
      } yield Option[ContinuationsWithBlockInfo](
        ContinuationsWithBlockInfo(continuationInfos, blockInfo)
      )
    } else {
      none[ContinuationsWithBlockInfo].pure[F]
    }

  private def isListeningNameReduced(
      block: BlockMessage,
      sortedListeningName: Seq[Par]
  ): Boolean = {
    val serializedLog = for {
      pd    <- block.body.deploys
      event <- pd.deployLog
    } yield event
    val log =
      serializedLog.map(EventConverter.toRspaceEvent)
    log.exists {
      case Produce(channelHash, _, _) =>
        assert(sortedListeningName.size == 1, "Produce can have only one channel")
        channelHash == StableHashProvider.hash(sortedListeningName.head)
      case Consume(channelsHashes, _, _) =>
        channelsHashes.toList.sorted == sortedListeningName
          .map(StableHashProvider.hash(_))
          .toList
          .sorted
      case COMM(consume, produces, _, _) =>
        (consume.channelsHashes.toList.sorted ==
          sortedListeningName.map(StableHashProvider.hash(_)).toList.sorted) ||
          produces.exists(
            produce => produce.channelsHash == StableHashProvider.hash(sortedListeningName)
          )
    }
  }

  private def toposortDag[
      F[_]: Monad: BlockDagStorage: Log: BlockStore,
      A
  ](depth: Int, maxDepthLimit: Int)(
      doIt: Vector[Vector[BlockHash]] => F[ApiErr[A]]
  ): F[ApiErr[A]] = {
    def response: F[ApiErr[A]] =
      for {
        dag               <- BlockDagStorage[F].getRepresentation
        latestBlockNumber <- dag.latestBlockNumber
        topoSort          <- dag.topoSort((latestBlockNumber - depth), none)
        result            <- doIt(topoSort)
      } yield result

    if (depth > maxDepthLimit)
      s"Your request depth ${depth} exceed the max limit ${maxDepthLimit}".asLeft[A].pure[F]
    else
      response
  }

  def getBlocksByHeights[F[_]: Sync: BlockDagStorage: Log: BlockStore](
      startBlockNumber: Long,
      endBlockNumber: Long,
      maxBlocksLimit: Int
  ): F[ApiErr[List[LightBlockInfo]]] = {
    def response: F[ApiErr[List[LightBlockInfo]]] =
      for {
        dag         <- BlockDagStorage[F].getRepresentation
        topoSortDag <- dag.topoSort(startBlockNumber, Some(endBlockNumber))
        result <- topoSortDag
                   .foldM(List.empty[LightBlockInfo]) {
                     case (blockInfosAtHeightAcc, blockHashesAtHeight) =>
                       for {
                         blocksAtHeight <- blockHashesAtHeight.traverse(BlockStore[F].getUnsafe)
                         blockInfosAtHeight <- blocksAtHeight.traverse(
                                                getLightBlockInfo[F]
                                              )
                       } yield blockInfosAtHeightAcc ++ blockInfosAtHeight
                   }
                   .map(_.asRight[Error])
      } yield result

    if (endBlockNumber - startBlockNumber > maxBlocksLimit)
      s"Your request startBlockNumber ${startBlockNumber} and endBlockNumber ${endBlockNumber} exceed the max limit ${maxBlocksLimit}"
        .asLeft[List[LightBlockInfo]]
        .pure[F]
    else
      response
  }

  def visualizeDag[F[_]: Monad: Sync: BlockDagStorage: Log: BlockStore, R](
      depth: Int,
      maxDepthLimit: Int,
      startBlockNumber: Int,
      visualizer: (Vector[Vector[BlockHash]], String) => F[Graphz[F]],
      serialize: F[R]
  ): F[ApiErr[R]] =
    for {
      dag <- BlockDagStorage[F].getRepresentation
      // the default startBlockNumber is 0
      // if the startBlockNumber is 0 , it would use the latestBlockNumber for backward compatible
      startBlockNum <- if (startBlockNumber == 0) dag.latestBlockNumber
                      else Sync[F].delay(startBlockNumber.toLong)
      topoSortDag <- dag.topoSort(
                      startBlockNum - depth,
                      Some(startBlockNum)
                    )
      _      <- visualizer(topoSortDag, PrettyPrinter.buildString(dag.lastFinalizedBlock))
      result <- serialize
    } yield result.asRight[Error]

  def machineVerifiableDag[
      F[_]: Monad: Sync: BlockDagStorage: Log: BlockStore
  ](depth: Int, maxDepthLimit: Int): F[ApiErr[String]] =
    toposortDag[F, String](depth, maxDepthLimit) { topoSort =>
      val fetchParents: BlockHash => F[List[BlockHash]] = { blockHash =>
        BlockStore[F].getUnsafe(blockHash) map (_.header.parentsHashList)
      }

      MachineVerifiableDag[F](topoSort, fetchParents)
        .map(_.map(edges => edges.show).mkString("\n"))
        .map(_.asRight[Error])
    }

  def getBlocks[F[_]: Sync: BlockDagStorage: Log: BlockStore](
      depth: Int,
      maxDepthLimit: Int
  ): F[ApiErr[List[LightBlockInfo]]] =
    toposortDag[F, List[LightBlockInfo]](depth, maxDepthLimit) { topoSort =>
      topoSort
        .foldM(List.empty[LightBlockInfo]) {
          case (blockInfosAtHeightAcc, blockHashesAtHeight) =>
            for {
              blocksAtHeight <- blockHashesAtHeight.traverse(BlockStore[F].getUnsafe)
              blockInfosAtHeight <- blocksAtHeight.traverse(
                                     getLightBlockInfo[F]
                                   )
            } yield blockInfosAtHeightAcc ++ blockInfosAtHeight
        }
        .map(_.reverse.asRight[Error])
    }

  def findDeploy[F[_]: Sync: BlockDagStorage: Log: BlockStore](
      id: DeployId
  ): F[ApiErr[LightBlockInfo]] =
    for {
      dag            <- BlockDagStorage[F].getRepresentation
      maybeBlockHash <- dag.lookupByDeployId(id)
      maybeBlock     <- maybeBlockHash.traverse(BlockStore[F].getUnsafe)
      response       <- maybeBlock.traverse(getLightBlockInfo[F])
    } yield response.fold(
      s"Couldn't find block containing deploy with id: ${PrettyPrinter
        .buildStringNoLimit(id)}".asLeft[LightBlockInfo]
    )(_.asRight)

  def getBlock[F[_]: Sync: BlockDagStorage: Log: BlockStore: Span](
      hash: String
  ): F[ApiErr[BlockInfo]] = Span[F].trace(GetBlockSource) {
    val response = for {
      // Add constraint on the length of searched hash to prevent to many block results
      // which can cause severe CPU load.
      _ <- BlockRetrievalError(s"Input hash value must be at least 6 characters: $hash")
            .raiseError[F, ApiErr[BlockInfo]]
            .whenA(hash.length < 6)
      // Check if hash string is in Base16 encoding and convert to ByteString
      hashByteString <- hash.hexToByteString
                         .liftTo[F](
                           BlockRetrievalError(
                             s"Input hash value is not valid hex string: $hash"
                           )
                         )
      // Check if hash is complete and not just the prefix in which case
      // we can use `get` directly and not iterate over the whole block hash index.
      getBlock  = BlockStore[F].get1(hashByteString)
      findBlock = findBlockFromStore[F](hash)
      blockOpt  <- if (hash.length == 64) getBlock else findBlock
      // Get block form the block store
      block <- blockOpt.liftTo(
                BlockRetrievalError(s"Error: Failure to find block with hash: $hash")
              )
      // Check if the block is added to the dag and convert it to block info
      dag <- BlockDagStorage[F].getRepresentation
      blockInfo <- dag
                    .contains(block.blockHash)
                    .ifM(
                      getFullBlockInfo[F](block),
                      BlockRetrievalError(
                        s"Error: Block with hash $hash received but not added yet"
                      ).raiseError
                    )
    } yield blockInfo

    response.map(_.asRight[String]).handleError {
      // Convert error message from BlockRetrievalError
      case BlockRetrievalError(errorMessage) => errorMessage.asLeft[BlockInfo]
    }
  }

  private def getBlockInfo[A, F[_]: Applicative: BlockStore](
      block: BlockMessage,
      constructor: (BlockMessage, Float) => A
  ): F[A] = constructor(block, -1f).pure[F]

  private def getFullBlockInfo[F[_]: Monad: BlockStore](
      block: BlockMessage
  ): F[BlockInfo] =
    getBlockInfo[BlockInfo, F](block, constructBlockInfo)

  def getLightBlockInfo[F[_]: Monad: BlockStore](
      block: BlockMessage
  ): F[LightBlockInfo] =
    getBlockInfo[LightBlockInfo, F](block, constructLightBlockInfo)

  private def constructBlockInfo(
      block: BlockMessage,
      faultTolerance: Float
  ): BlockInfo = {
    val lightBlockInfo = constructLightBlockInfo(block, faultTolerance)
    val deploys        = block.body.deploys.map(_.toDeployInfo)
    BlockInfo(blockInfo = lightBlockInfo, deploys = deploys)
  }

  private def constructLightBlockInfo(
      block: BlockMessage,
      faultTolerance: Float
  ): LightBlockInfo =
    LightBlockInfo(
      blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
      sender = PrettyPrinter.buildStringNoLimit(block.sender),
      seqNum = block.seqNum.toLong,
      sig = PrettyPrinter.buildStringNoLimit(block.sig),
      sigAlgorithm = block.sigAlgorithm,
      shardId = block.shardId,
      extraBytes = block.extraBytes,
      version = block.header.version,
      timestamp = block.header.timestamp,
      headerExtraBytes = block.header.extraBytes,
      parentsHashList = block.header.parentsHashList.map(PrettyPrinter.buildStringNoLimit),
      blockNumber = block.body.state.blockNumber,
      preStateHash = PrettyPrinter.buildStringNoLimit(block.body.state.preStateHash),
      postStateHash = PrettyPrinter.buildStringNoLimit(block.body.state.postStateHash),
      bodyExtraBytes = block.body.extraBytes,
      bonds = block.body.state.bonds.map(ProtoUtil.bondToBondInfo),
      blockSize = block.toProto.serializedSize.toString,
      deployCount = block.body.deploys.length,
      faultTolerance = faultTolerance,
      justifications = block.justifications.map(ProtoUtil.justificationsToJustificationInfos),
      rejectedDeploys = block.body.rejectedDeploys.map(
        r => RejectedDeployInfo(PrettyPrinter.buildStringNoLimit(r.sig))
      )
    )

  // Be careful to use this method , because it would iterate the whole indexes to find the matched one which would cause performance problem
  // Trying to use BlockStore.get as much as possible would more be preferred
  private def findBlockFromStore[F[_]: Monad: BlockDagStorage: BlockStore](
      hash: String
  ): F[Option[BlockMessage]] =
    for {
      dag          <- BlockDagStorage[F].getRepresentation
      blockHashOpt <- dag.find(hash)
      message      <- blockHashOpt.flatTraverse(BlockStore[F].get1)
    } yield message

  def previewPrivateNames[F[_]: Monad: Log](
      deployer: ByteString,
      timestamp: Long,
      nameQty: Int
  ): F[ApiErr[Seq[ByteString]]] = {
    val rand                = Tools.unforgeableNameRng(PublicKey(deployer.toByteArray), timestamp)
    val safeQty             = nameQty min 1024
    val ids: Seq[BlockHash] = (0 until safeQty).map(_ => ByteString.copyFrom(rand.next()))
    ids.asRight[String].pure[F]
  }

  def lastFinalizedBlock[F[_]: Sync: BlockDagStorage: BlockStore: Log]: F[ApiErr[BlockInfo]] =
    for {
      dag                <- BlockDagStorage[F].getRepresentation
      lastFinalizedBlock <- BlockStore[F].getUnsafe(dag.lastFinalizedBlock)
      blockInfo          <- getFullBlockInfo[F](lastFinalizedBlock)
    } yield blockInfo.asRight

  def isFinalized[F[_]: Monad: BlockDagStorage: BlockStore: Log](
      hash: String
  ): F[ApiErr[Boolean]] =
    for {
      dag            <- BlockDagStorage[F].getRepresentation
      givenBlockHash = hash.unsafeHexToByteString
      result         <- dag.isFinalized(givenBlockHash)
    } yield result.asRight[Error]

  def bondStatus[F[_]: Sync: RuntimeManager: BlockDagStorage: BlockStore: Log](
      publicKey: ByteString,
      targetBlock: Option[BlockMessage] = none[BlockMessage]
  ): F[ApiErr[Boolean]] =
    for {
      dag                <- BlockDagStorage[F].getRepresentation
      lastFinalizedBlock <- BlockStore[F].getUnsafe(dag.lastFinalizedBlock)
      postStateHash      = ProtoUtil.postStateHash(targetBlock.getOrElse(lastFinalizedBlock))
      bonds              <- RuntimeManager[F].computeBonds(postStateHash)
      validatorBondOpt   = bonds.find(_.validator == publicKey)
    } yield validatorBondOpt.isDefined.asRight[Error]

  /**
    * Explore the data or continuation in the tuple space for specific blockHash
    *
    * @param term: the term you want to explore in the request. Be sure the first new should be `return`
    * @param blockHash: the block hash you want to explore
    * @param usePreStateHash: Each block has preStateHash and postStateHash. If usePreStateHash is true, the explore
    *                       would try to execute on preState.
    * */
  def exploratoryDeploy[F[_]: Sync: EngineCell: Log: BlockStore](
      term: String,
      blockHash: Option[String] = none,
      usePreStateHash: Boolean = false,
      devMode: Boolean = false
  ): F[ApiErr[(Seq[Par], LightBlockInfo)]] = {
    val errorMessage =
      "Could not execute exploratory deploy, casper instance was not available yet."
    EngineCell[F].read >>= (
      _.withCasper(
        implicit casper =>
          for {
            isReadOnly <- casper.getValidator.map(_.isEmpty)
            result <- if (isReadOnly || devMode) {
                       for {
                         targetBlock <- if (blockHash.isEmpty)
                                         casper.lastFinalizedBlock.map(_.some)
                                       else
                                         for {
                                           hashByteString <- blockHash
                                                              .getOrElse("")
                                                              .hexToByteString
                                                              .liftTo[F](
                                                                BlockRetrievalError(
                                                                  s"Input hash value is not valid hex string: $blockHash"
                                                                )
                                                              )
                                           block <- BlockStore[F].get1(hashByteString)
                                         } yield block
                         res <- targetBlock.traverse(b => {
                                 val postStateHash =
                                   if (usePreStateHash) ProtoUtil.preStateHash(b)
                                   else ProtoUtil.postStateHash(b)
                                 for {
                                   runtimeManager <- casper.getRuntimeManager
                                   res <- runtimeManager
                                           .playExploratoryDeploy(term, postStateHash)
                                   lightBlockInfo <- getLightBlockInfo[F](b)
                                 } yield (res, lightBlockInfo)
                               })
                       } yield res.fold(
                         s"Can not find block ${blockHash}".asLeft[(Seq[Par], LightBlockInfo)]
                       )(_.asRight[Error])
                     } else {
                       "Exploratory deploy can only be executed on read-only RNode."
                         .asLeft[(Seq[Par], LightBlockInfo)]
                         .pure[F]
                     }
          } yield result,
        Log[F].warn(errorMessage).as(s"Error: $errorMessage".asLeft)
      )
    )
  }

  sealed trait LatestBlockMessageError     extends Throwable
  final case object ValidatorReadOnlyError extends LatestBlockMessageError
  final case object NoBlockMessageError    extends LatestBlockMessageError

  def getLatestMessage[F[_]: Sync: EngineCell: Log]: F[ApiErr[BlockMetadata]] = {
    val errorMessage =
      "Could not execute exploratory deploy, casper instance was not available yet."
    EngineCell[F].read >>= (
      _.withCasper(
        implicit casper =>
          for {
            validatorOpt     <- casper.getValidator
            validator        <- validatorOpt.liftTo[F](ValidatorReadOnlyError)
            dag              <- casper.blockDag
            latestMessageOpt <- dag.latestMessage(ByteString.copyFrom(validator.publicKey.bytes))
            latestMessage    <- latestMessageOpt.liftTo[F](NoBlockMessageError)
          } yield latestMessage.asRight[Error],
        Log[F].warn(errorMessage).as(s"Error: $errorMessage".asLeft)
      )
    )
  }

  def getDataAtPar[F[_]: Concurrent: RuntimeManager: BlockDagStorage: Log: BlockStore](
      par: Par,
      blockHash: String,
      usePreStateHash: Boolean
  ): F[ApiErr[(Seq[Par], LightBlockInfo)]] =
    for {
      block     <- BlockStore[F].getUnsafe(blockHash.unsafeHexToByteString)
      sortedPar <- parSortable.sortMatch[F](par).map(_.term)
      data      <- getDataWithBlockInfo(RuntimeManager[F], sortedPar, block).map(_.get)
    } yield (data.postBlockData, data.block).asRight[Error]
}
