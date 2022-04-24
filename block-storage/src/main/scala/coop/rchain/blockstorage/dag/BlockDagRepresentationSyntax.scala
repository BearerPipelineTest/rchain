package coop.rchain.blockstorage.dag

import cats.Applicative
import cats.data.OptionT
import cats.effect.Sync
import cats.syntax.all._
import coop.rchain.blockstorage.syntax._
import coop.rchain.casper.PrettyPrinter
import coop.rchain.casper.protocol.Justification
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.BlockMetadata
import coop.rchain.models.Validator.Validator
import coop.rchain.models.syntax._
import coop.rchain.shared.syntax._
import fs2.Stream

import scala.collection.immutable.SortedMap

trait DagRepresentationSyntax {
  implicit final def blockStorageSyntaxDagRepresentation[F[_]](
      dag: DagRepresentation
  ): DagRepresentationOps[F] = new DagRepresentationOps[F](dag)
}

final case class BlockDagInconsistencyError(message: String) extends Exception(message)
final case class NoLatestMessage(message: String)            extends Exception(message)

final class DagRepresentationOps[F[_]](
    // DagRepresentation extensions / syntax
    private val dag: DagRepresentation
) extends AnyVal {

  def contains(blockHash: BlockHash)(implicit a: Applicative[F]): F[Boolean] =
    DagRepresentation.contains(dag, blockHash)

  def children(blockHash: BlockHash)(implicit a: Applicative[F]): F[Option[Set[BlockHash]]] =
    DagRepresentation.children(dag, blockHash)

  def lastFinalizedBlock(implicit s: Sync[F]): F[BlockHash] =
    DagRepresentation.lastFinalizedBlock(dag)

  def isFinalized(blockHash: BlockHash)(implicit a: Applicative[F]): F[Boolean] =
    DagRepresentation.isFinalized(dag, blockHash)

  def getHeightMap(implicit a: Applicative[F]): F[SortedMap[Long, Set[BlockHash]]] =
    DagRepresentation.getHeightMap(dag)

  def latestBlockNumber(implicit a: Applicative[F]): F[Long] =
    DagRepresentation.latestBlockNumber(dag)

  def topoSort(
      startBlockNumber: Long,
      maybeEndBlockNumber: Option[Long]
  )(implicit s: Sync[F]): F[Vector[Vector[BlockHash]]] =
    DagRepresentation.topoSort(dag, startBlockNumber, maybeEndBlockNumber)

  def find(truncatedHash: String)(implicit s: Sync[F]): F[Option[BlockHash]] =
    DagRepresentation.find(dag, truncatedHash)

  def invalidBlocks(implicit s: Sync[F], bds: BlockDagStorage[F]): F[Set[BlockMetadata]] =
    DagRepresentation.invalidBlocks(dag)

  def latestMessageHash(
      validator: Validator
  )(implicit s: Sync[F], bds: BlockDagStorage[F]): F[Option[BlockHash]] =
    DagRepresentation.latestMessageHash(dag, validator)

  def latestMessageHashes(
      implicit s: Sync[F],
      bds: BlockDagStorage[F]
  ): F[Map[Validator, BlockHash]] =
    DagRepresentation.latestMessageHashes(dag)

  def latestMessageHashUnsafe(
      v: Validator
  )(implicit sync: Sync[F], bds: BlockDagStorage[F]): F[BlockHash] = {
    def errMsg = s"No latest message for validator ${PrettyPrinter.buildString(v)}"
    latestMessageHash(v) >>= (_.liftTo(NoLatestMessage(errMsg)))
  }

  def latestMessage(
      validator: Validator
  )(implicit sync: Sync[F], bds: BlockDagStorage[F]): F[Option[BlockMetadata]] =
    latestMessageHash(validator) >>= (_.traverse(bds.lookupUnsafe))

  def latestMessages(
      implicit sync: Sync[F],
      bds: BlockDagStorage[F]
  ): F[Map[Validator, BlockMetadata]] = {
    import cats.instances.vector._
    latestMessageHashes >>= (
      _.toVector
        .traverse {
          case (validator, hash) => bds.lookupUnsafe(hash).map(validator -> _)
        }
        .map(_.toMap)
      )
  }

  def invalidLatestMessages(
      implicit sync: Sync[F],
      bds: BlockDagStorage[F]
  ): F[Map[Validator, BlockHash]] =
    latestMessages.flatMap(
      lm =>
        invalidLatestMessages(lm.map {
          case (validator, block) => (validator, block.blockHash)
        })
    )

  def invalidLatestMessages(latestMessagesHashes: Map[Validator, BlockHash])(
      implicit sync: Sync[F],
      bds: BlockDagStorage[F]
  ): F[Map[Validator, BlockHash]] =
    invalidBlocks.map { invalidBlocks =>
      latestMessagesHashes.filter {
        case (_, blockHash) => invalidBlocks.map(_.blockHash).contains(blockHash)
      }
    }

  def invalidBlocksMap(
      implicit sync: Sync[F],
      bds: BlockDagStorage[F]
  ): F[Map[BlockHash, Validator]] =
    for {
      ib <- invalidBlocks
      r  = ib.map(block => (block.blockHash, block.sender)).toMap
    } yield r

  def selfJustificationChain(
      h: BlockHash
  )(implicit sync: Sync[F], bds: BlockDagStorage[F]): Stream[F, Justification] =
    Stream.unfoldEval(h)(
      message =>
        bds
          .lookupUnsafe(message)
          .map { v =>
            v.justifications.find(_.validator == v.sender)
          }
          .map(_.map(next => (next, next.latestBlockHash)))
    )

  def selfJustification(
      h: BlockHash
  )(implicit sync: Sync[F], bds: BlockDagStorage[F]): F[Option[Justification]] =
    selfJustificationChain(h).head.compile.last

  def mainParentChain(h: BlockHash, stopAtHeight: Long = 0)(
      implicit sync: Sync[F],
      bds: BlockDagStorage[F]
  ): Stream[F, BlockHash] =
    Stream.unfoldEval(h) { message =>
      bds
        .lookupUnsafe(message)
        .map(
          meta =>
            if (meta.blockNum <= stopAtHeight)
              none[(BlockHash, BlockHash)]
            else
              meta.parents.headOption.map(v => (v, v))
        )
    }

  def isInMainChain(ancestor: BlockHash, descendant: BlockHash)(
      implicit sync: Sync[F],
      bds: BlockDagStorage[F]
  ): F[Boolean] = {
    val result = OptionT(bds.lookup(ancestor).map(_.map(_.blockNum))).semiflatMap { aHeight =>
      mainParentChain(descendant, aHeight)
        .filter(_ == ancestor)
        .head
        .compile
        .last
        .map(_.isDefined)
    }
    (descendant == ancestor).pure ||^ result.getOrElse(false)
  }

  def parentsUnsafe(
      item: BlockHash
  )(implicit sync: Sync[F], bds: BlockDagStorage[F]): F[List[BlockHash]] = {
    def errMsg = s"Parents lookup failed: DAG is missing ${item.show}"
    bds.lookup(item).map(_.map(v => v.parents)) >>= (_.liftTo(BlockDagInconsistencyError(errMsg)))
  }

  def nonFinalizedBlocks(implicit sync: Sync[F], bds: BlockDagStorage[F]): F[Set[BlockHash]] =
    Stream
      .unfoldLoopEval(dag.latestMessagesHashes.valuesIterator.toList) { lvl =>
        for {
          out  <- lvl.filterA(dag.isFinalized(_).not)
          next <- out.traverse(bds.lookup(_).map(_.map(_.parents))).map(_.flatten.flatten)
        } yield (out, next.nonEmpty.guard[Option].as(next))
      }
      .flatMap(Stream.emits)
      .compile
      .to(Set)

  def descendants(
      blockHash: BlockHash
  )(implicit sync: Sync[F]): F[Set[BlockHash]] =
    Stream
      .unfoldLoopEval(List(blockHash)) { lvl =>
        for {
          out  <- lvl.traverse(dag.children(_)(sync)).map(_.flatten.flatten)
          next = out
        } yield (out, next.nonEmpty.guard[Option].as(next))
      }
      .flatMap(Stream.emits)
      .compile
      .to(Set)

  def ancestors(blockHash: BlockHash, filterF: BlockHash => F[Boolean])(
      implicit sync: Sync[F],
      bds: BlockDagStorage[F]
  ): F[Set[BlockHash]] =
    Stream
      .unfoldEval(List(blockHash)) { lvl =>
        val parents = lvl
          .traverse(bds.lookupUnsafe)
          .flatMap(_.flatMap(_.parents).distinct.filterA(filterF))
        parents.map(p => p.nonEmpty.guard[Option].as(p, p))
      }
      .flatMap(Stream.emits)
      .compile
      .to(Set)

  def withAncestors(blockHash: BlockHash, filterF: BlockHash => F[Boolean])(
      implicit sync: Sync[F],
      bds: BlockDagStorage[F]
  ): F[Set[BlockHash]] =
    ancestors(blockHash, filterF).map(_ + blockHash)

  // TODO replace all calls with direct calls for BlockDagStorage
  def lookup(blockHash: BlockHash)(implicit bds: BlockDagStorage[F]) = bds.lookup(blockHash)
  def lookupUnsafe(blockHash: BlockHash)(implicit s: Sync[F], bds: BlockDagStorage[F]) =
    bds.lookupUnsafe(blockHash)
}
