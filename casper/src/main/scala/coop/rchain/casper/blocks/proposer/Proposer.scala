package coop.rchain.casper.blocks.proposer

import cats.effect.concurrent.Deferred
import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.BlockStore.BlockStore
import coop.rchain.blockstorage.dag.BlockDagStorage
import coop.rchain.casper._
import coop.rchain.casper.protocol.{BlockMessage, CommUtil}
import coop.rchain.casper.rholang.RuntimeManager
import coop.rchain.casper.syntax._
import coop.rchain.crypto.PrivateKey
import coop.rchain.metrics.Metrics.Source
import coop.rchain.metrics.implicits._
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.Validator.Validator
import coop.rchain.shared.{EventPublisher, Log, Time}

sealed abstract class ProposerResult
object ProposerEmpty                                                         extends ProposerResult
final case class ProposerSuccess(status: ProposeStatus, block: BlockMessage) extends ProposerResult
final case class ProposerFailure(status: ProposeStatus, seqNumber: Long)     extends ProposerResult
final case class ProposerStarted(seqNumber: Long)                            extends ProposerResult

object ProposerResult {
  def empty: ProposerResult = ProposerEmpty
  def success(status: ProposeStatus, block: BlockMessage): ProposerResult =
    ProposerSuccess(status, block)
  def failure(status: ProposeStatus, seqNumber: Long): ProposerResult =
    ProposerFailure(status, seqNumber)
  def started(seqNumber: Long): ProposerResult = ProposerStarted(seqNumber)
}

class Proposer[F[_]: Concurrent: Log: Span](
    getLatestSeqNumber: Validator => F[Long],
    // propose constraint checkers
    checkActiveValidator: ValidatorIdentity => F[Boolean],
    createBlock: ValidatorIdentity => F[BlockCreatorResult],
    validateBlock: BlockMessage => F[ValidBlockProcessing],
    proposeEffect: BlockMessage => F[Unit],
    validator: ValidatorIdentity
) {

  implicit val RuntimeMetricsSource: Source = Metrics.Source(CasperMetricsSource, "proposer")
  // This is the whole logic of propose
  private def doPropose: F[(ProposeResult, Option[BlockMessage])] =
    Span[F].traceI("do-propose") {
      // check if node is allowed to propose a block
      checkActiveValidator(validator).ifM(
        for {
          b <- createBlock(validator)
          r <- b match {
                case NoNewDeploys =>
                  (ProposeResult.failure(NoNewDeploys), none[BlockMessage]).pure[F]
                case Created(b) =>
                  validateBlock(b).flatMap {
                    case Right(v) =>
                      proposeEffect(b) >>
                        (ProposeResult.success(v), b.some).pure[F]
                    case Left(v) =>
                      Concurrent[F].raiseError[(ProposeResult, Option[BlockMessage])](
                        new Exception(
                          s"Validation of self created block failed with reason: $v, cancelling propose."
                        )
                      )
                  }
              }
        } yield r,
        (ProposeResult.failure(NotBonded), none[BlockMessage]).pure[F]
      )
    }

  def propose(
      isAsync: Boolean,
      proposeIdDef: Deferred[F, ProposerResult]
  ): F[(ProposeResult, Option[BlockMessage])] = {
    val valBytes = ByteString.copyFrom(validator.publicKey.bytes)
    for {
      validatorSeqNum <- getLatestSeqNumber(valBytes)
      nextSeq         = validatorSeqNum + 1L
      result <- if (isAsync) for {
                 _ <- proposeIdDef.complete(ProposerResult.started(nextSeq))

                 // propose
                 r <- doPropose
               } yield r
               else
                 for {
                   // propose
                   r <- doPropose

                   (result, blockHashOpt) = r
                   proposerResult = blockHashOpt.fold {
                     ProposerResult.failure(result.proposeStatus, nextSeq)
                   } { block =>
                     ProposerResult.success(result.proposeStatus, block)
                   }
                   _ <- proposeIdDef.complete(proposerResult)
                 } yield r

    } yield result
  }
}

object Proposer {
  // format: off
  def apply[F[_]
    /* Execution */   : Concurrent: Timer: Time
    /* Storage */     : BlockStore: BlockDagStorage
    /* Diagnostics */ : Log: Span: Metrics: EventPublisher
    /* Comm */        : CommUtil: RuntimeManager
  ] // format: on
  (
      validatorIdentity: ValidatorIdentity,
      shardId: String,
      minPhloPrice: Long,
      dummyDeployOpt: Option[(PrivateKey, String)] = None
  ): Proposer[F] = {
    // TODO: refactor proposer to get this from parent pre state
    def getLatestSeqNumber(sender: Validator): F[Long] =
      for {
        dag        <- BlockDagStorage[F].getRepresentation
        latestMsgs = dag.dagMessageState.latestMsgs
        maxSeqNum  = latestMsgs.find(_.sender == sender).map(_.senderSeq)
      } yield maxSeqNum.getOrElse(-1)

    def createBlock(validatorIdentity: ValidatorIdentity) =
      for {
        parentsPreState <- MultiParentCasper.getPreStateForNewBlock
        result          <- BlockCreator.create(parentsPreState, validatorIdentity, shardId, dummyDeployOpt)
      } yield result

    def validateBlock(block: BlockMessage) =
      MultiParentCasper.validate(block, shardId, minPhloPrice).flatMap { result =>
        result
          .map { blockMeta =>
            BlockDagStorage[F].insertNew(blockMeta, block).as(BlockStatus.valid.asRight[BlockError])
          }
          .leftMap {
            case (_, err) =>
              new Exception(s"Unexpected fatal error when replay own block: $err")
                .raiseError[F, ValidBlockProcessing]
          }
          .merge
      }

    def checkValidatorIsActive(validator: ValidatorIdentity): F[Boolean] =
      for {
        dag          <- BlockDagStorage[F].getRepresentation
        latestFringe = dag.dagMessageState.latestFringe
        // TODO: take bonds map from merged state of fringe
        //  - it should also include consensus bonds map
        bondsMap <- if (latestFringe.nonEmpty) latestFringe.head.bondsMap.pure[F]
                   else dag.lookupUnsafe(dag.heightMap.head._2.head).map(_.bondsMap)
        sender = ByteString.copyFrom(validator.publicKey.bytes)
      } yield bondsMap.contains(sender)

    val proposeEffect = (b: BlockMessage) =>
      // store block
      BlockStore[F].put(b) >>
        // save changes to Casper
        // TODO: temp this is done after validation
//        MultiParentCasper.handleValidBlock(b) >>
        // broadcast hash to peers
        CommUtil[F].sendBlockHash(b.blockHash, b.sender) >>
        // Publish event
        EventPublisher[F].publish(MultiParentCasper.createdEvent(b))

    new Proposer(
      getLatestSeqNumber,
      checkValidatorIsActive,
      createBlock,
      validateBlock,
      proposeEffect,
      validatorIdentity
    )
  }
}
