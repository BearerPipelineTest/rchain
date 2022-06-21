package coop.rchain.casper.blocks.proposer

import cats.effect.Concurrent
import cats.instances.list._
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore.BlockStore
import coop.rchain.blockstorage.dag.BlockDagStorage
import coop.rchain.blockstorage.syntax._
import coop.rchain.casper.protocol._
import coop.rchain.casper.rholang.InterpreterUtil.computeParentsPostState
import coop.rchain.casper.rholang.RuntimeManager.StateHash
import coop.rchain.casper.rholang.sysdeploys.{CloseBlockDeploy, SlashDeploy}
import coop.rchain.casper.rholang.{InterpreterUtil, RuntimeManager, SystemDeployUtil}
import coop.rchain.casper.util.{ConstructDeploy, ProtoUtil}
import coop.rchain.casper.{CasperSnapshot, PrettyPrinter, ValidatorIdentity}
import coop.rchain.crypto.{PrivateKey, PublicKey}
import coop.rchain.crypto.signatures.Signed
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.Validator.Validator
import coop.rchain.rholang.interpreter.SystemProcesses.BlockData
import coop.rchain.shared.{Log, Stopwatch, Time}

object BlockCreator {
  private[this] val ProcessDeploysAndCreateBlockMetricsSource =
    Metrics.Source(Metrics.BaseSource, "create-block")

  /*
   * Overview of createBlock
   *
   *  1. Rank each of the block cs's latest messages (blocks) via the LMD GHOST estimator.
   *  2. Let each latest message have a score of 2^(-i) where i is the index of that latest message in the ranking.
   *     Take a subset S of the latest messages such that the sum of scores is the greatest and
   *     none of the blocks in S conflicts with each other. S will become the parents of the
   *     about-to-be-created block.
   *  3. Extract all valid deploys that aren't already in all ancestors of S (the parents).
   *  4. Create a new block that contains the deploys from the previous step.
   */
  def create[F[_]: Concurrent: Log: Time: BlockStore: BlockDagStorage: Metrics: RuntimeManager: Span](
      s: CasperSnapshot,
      validatorIdentity: ValidatorIdentity,
      dummyDeployOpt: Option[(PrivateKey, String)] = None
  )(implicit runtimeManager: RuntimeManager[F]): F[BlockCreatorResult] =
    Span[F].trace(ProcessDeploysAndCreateBlockMetricsSource) {
      val selfId         = ByteString.copyFrom(validatorIdentity.publicKey.bytes)
      val nextSeqNum     = s.maxSeqNums.get(selfId).map(_ + 1L).getOrElse(0L)
      val nextBlockNum   = s.maxBlockNum + 1
      val justifications = s.justifications.map(_.blockHash).toList

      def prepareUserDeploys(blockNumber: Long): F[Set[Signed[DeployData]]] =
        for {
          unfinalized         <- BlockDagStorage[F].pooledDeploys.map(_.values.toSet)
          earliestBlockNumber = blockNumber - s.onChainState.shardConf.deployLifespan
          valid = unfinalized.filter(
            d =>
              notFutureDeploy(blockNumber, d.data) &&
                notExpiredDeploy(earliestBlockNumber, d.data)
          )
          // this is required to prevent resending the same deploy several times by validator
          validUnique = valid -- s.deploysInScope
        } yield validUnique

      def prepareSlashingDeploys(seqNum: Long): F[Seq[SlashDeploy]] =
        for {
          ilm <- s.dag.invalidLatestMessages
          // if the node is already not active as per main parent, the node won't slash once more
          ilmFromBonded = ilm.toList.filter {
            case (validator, _) => s.onChainState.bondsMap.getOrElse(validator, 0L) > 0L
          }
          slashingDeploysWithBlocks = ilmFromBonded.map {
            case (slashedValidator, invalidBlock) =>
              val rnd = SystemDeployUtil.generateSlashDeployRandomSeed(selfId, seqNum)
              (SlashDeploy(slashedValidator, rnd), invalidBlock)
          }
          slashingDeploys <- slashingDeploysWithBlocks.traverse {
                              case (sd, invalidBlock) =>
                                Log[F]
                                  .info(
                                    s"Issuing slashing deploy justified by block ${PrettyPrinter.buildString(invalidBlock)}"
                                  )
                                  .as(sd)
                            }
        } yield slashingDeploys

      def prepareDummyDeploy(blockNumber: Long, shardId: String): Seq[Signed[DeployData]] =
        dummyDeployOpt match {
          case Some((privateKey, term)) =>
            Seq(
              ConstructDeploy.sourceDeployNow(
                source = term,
                sec = privateKey,
                vabn = blockNumber - 1,
                shardId = shardId
              )
            )
          case None => Seq.empty[Signed[DeployData]]
        }

      val createBlockProcess = for {
        _ <- Log[F].info(
              s"Creating block #${nextBlockNum} (seqNum ${nextSeqNum})"
            )
        shardId         = s.onChainState.shardConf.shardName
        userDeploys     <- prepareUserDeploys(nextBlockNum)
        dummyDeploys    = prepareDummyDeploy(nextBlockNum, shardId)
        slashingDeploys <- prepareSlashingDeploys(nextSeqNum)
        // make sure closeBlock is the last system Deploy
        systemDeploys = slashingDeploys :+ CloseBlockDeploy(
          SystemDeployUtil
            .generateCloseDeployRandomSeed(selfId, nextSeqNum)
        )
        deploys = userDeploys -- s.deploysInScope ++ dummyDeploys
        r <- if (deploys.nonEmpty || slashingDeploys.nonEmpty) {
              val blockData = BlockData(nextBlockNum, validatorIdentity.publicKey, nextSeqNum)
              for {
                computedParentsInfo <- computeParentsPostState(justifications, s, runtimeManager)
                checkpointData <- InterpreterUtil.computeDeploysCheckpoint(
                                   deploys.toSeq,
                                   systemDeploys,
                                   runtimeManager,
                                   blockData,
                                   computedParentsInfo
                                 )
                (
                  preStateHash,
                  postStateHash,
                  processedDeploys,
                  rejectedDeploys,
                  processedSystemDeploys
                )             = checkpointData
                newBonds      <- runtimeManager.computeBonds(postStateHash)
                _             <- Span[F].mark("before-packing-block")
                casperVersion = s.onChainState.shardConf.blockVersion
                // unsignedBlock got blockHash(hashed without signature)
                unsignedBlock = packageBlock(
                  validatorIdentity.publicKey,
                  blockData,
                  justifications,
                  preStateHash,
                  postStateHash,
                  processedDeploys,
                  rejectedDeploys.toList,
                  processedSystemDeploys,
                  newBonds,
                  shardId,
                  casperVersion
                )
                _ <- Span[F].mark("block-created")
                // signedBlock add signature and replace hashed-without-signature
                // blockHash to hashed-with-signature blockHash
                signedBlock = validatorIdentity.signBlock(unsignedBlock)
                _           <- Span[F].mark("block-signed")
              } yield BlockCreatorResult.created(signedBlock)
            } else
              BlockCreatorResult.noNewDeploys.pure[F]
      } yield r

      for {
        // Create block and measure duration
        r                      <- Stopwatch.duration(createBlockProcess)
        (blockStatus, elapsed) = r
        _ <- blockStatus match {
              case Created(block) =>
                val blockInfo   = PrettyPrinter.buildString(block, short = true)
                val deployCount = block.state.deploys.size
                Log[F].info(s"Block created: $blockInfo (${deployCount}d) [$elapsed]")
              case _ => ().pure[F]
            }
      } yield blockStatus
    }

  private def packageBlock(
      sender: PublicKey,
      blockData: BlockData,
      justifications: List[BlockHash],
      preStateHash: StateHash,
      postStateHash: StateHash,
      deploys: Seq[ProcessedDeploy],
      rejectedDeploys: List[ByteString],
      systemDeploys: Seq[ProcessedSystemDeploy],
      bondsMap: Map[Validator, Long],
      shardId: String,
      version: Int
  ): BlockMessage = {
    val state = RholangState(deploys.toList, systemDeploys.toList)
    ProtoUtil.unsignedBlockProto(
      version,
      shardId,
      blockData.blockNumber,
      sender,
      blockData.seqNum,
      preStateHash,
      postStateHash,
      justifications,
      bondsMap,
      rejectedDeploys,
      state
    )
  }

  private def notExpiredDeploy(earliestBlockNumber: Long, d: DeployData): Boolean =
    d.validAfterBlockNumber > earliestBlockNumber

  private def notFutureDeploy(currentBlockNumber: Long, d: DeployData): Boolean =
    d.validAfterBlockNumber < currentBlockNumber
}
