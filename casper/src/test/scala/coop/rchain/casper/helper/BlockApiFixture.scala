package coop.rchain.casper.helper

import cats.effect.Concurrent
import cats.syntax.all._
import coop.rchain.blockstorage.blockStore.BlockStore
import coop.rchain.blockstorage.dag.BlockDagStorage
import coop.rchain.blockstorage.deploy.DeployStorage
import coop.rchain.casper.ValidatorIdentity
import coop.rchain.casper.api.BlockApiImpl
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.comm.rp.Connect.Connection
import coop.rchain.comm.{Endpoint, NodeIdentifier, PeerNode}
import coop.rchain.metrics.Span
import coop.rchain.shared.Log

trait BlockApiFixture {

  def createBlockApi[F[_]: Concurrent: RuntimeManager: BlockDagStorage: DeployStorage: BlockStore: Log: Span](
      shardId: String,
      maxDepthLimit: Int,
      validatorIdOpt: Option[ValidatorIdentity] = none
  ): F[BlockApiImpl[F]] = {
    val thisNode = peerNode("testNode", 1234)
    BlockApiImpl[F](
      validatorOpt = validatorIdOpt,
      networkId = "rchain",
      shardId = shardId,
      minPhloPrice = 1,
      version = "",
      (thisNode, List[Connection](), Seq[PeerNode]()).pure[F],
      isNodeReadOnly = validatorIdOpt.isEmpty,
      maxDepthLimit = maxDepthLimit,
      devMode = false,
      triggerPropose = none,
      proposerStateRefOpt = none
    )
  }

  def createBlockApi[F[_]: Concurrent](node: TestNode[F]): F[BlockApiImpl[F]] = {
    import node.{blockDagStorage, blockStore, ds, logEff, runtimeManager, sp}

    val thisNode = node.local
    BlockApiImpl[F](
      validatorOpt = node.validatorIdOpt,
      networkId = "rchain",
      shardId = node.casperShardConf.shardName,
      minPhloPrice = 1,
      version = "",
      (thisNode, List[Connection](), Seq[PeerNode]()).pure[F],
      isNodeReadOnly = node.validatorIdOpt.isEmpty,
      maxDepthLimit = node.apiMaxBlocksLimit,
      devMode = false,
      triggerPropose = none,
      proposerStateRefOpt = none
    )
  }

  protected def endpoint(port: Int): Endpoint = Endpoint("host", port, port)

  protected def peerNode(name: String, port: Int): PeerNode =
    PeerNode(NodeIdentifier(name.getBytes), endpoint(port))
}
