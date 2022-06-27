package coop.rchain.casper.genesis

import cats.effect.Concurrent
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.casper.BlockRandomSeed
import coop.rchain.casper.genesis.contracts._
import coop.rchain.casper.protocol._
import coop.rchain.casper.rholang.RuntimeManager.StateHash
import coop.rchain.casper.rholang.RuntimeManager
import coop.rchain.casper.util.ProtoUtil.unsignedBlockProto
import coop.rchain.casper.{PrettyPrinter, ValidatorIdentity}
import coop.rchain.crypto.PublicKey
import coop.rchain.casper.rholang.RuntimeManager.{emptyStateHashFixed, StateHash}
import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.crypto.signatures.Signed
import coop.rchain.models.Par
import coop.rchain.models.syntax._
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.models.BlockVersion

final case class Genesis(
    sender: PublicKey,
    shardId: String,
    blockNumber: Long,
    proofOfStake: ProofOfStake,
    registry: Registry,
    vaults: Seq[Vault]
)

object Genesis {
  val genesisPubKey = PublicKey(Array[Byte]())

  def nonNegativeMergeableTagName(
      shardId: String,
      validatorKey: PublicKey,
      blockNumber: Long
  ): Par = {
    // NonNegative contract is the 4th contract deployed in the genesis, start from 0. Index should be 3
    val nonNegativeContractIndex: Byte = 3
    val rand = BlockRandomSeed
      .generateSplitRandomNumber(
        BlockRandomSeed(
          shardId,
          blockNumber,
          validatorKey,
          Blake2b256Hash.fromByteString(emptyStateHashFixed)
        ),
        nonNegativeContractIndex,
        BlockRandomSeed.UserDeploySplitIndex
      )
    val unforgeableByte = Iterator.continually(rand.next()).drop(1).next()
    unforgeableByte.toParUnforgeableName
  }

  // TODO config this with different name with different chain(shard)
  // https://github.com/rchain/rchain/issues/3685
  def MainnetNonNegativeMergeableTagName: Par = {
    val rand = Blake2b512Random.defaultRandom
    import coop.rchain.models.rholang.implicits._
    rand.next().toParUnforgeableName
  }

  def defaultBlessedTerms(
      posParams: ProofOfStake,
      registry: Registry,
      vaults: Seq[Vault],
      shardId: String
  ): Seq[Signed[DeployData]] = {
    // Splits initial vaults creation in multiple deploys (batches)
    val vaultBatches = vaults.grouped(100).toSeq
    val vaultDeploys = vaultBatches.zipWithIndex.map {
      case (batchVaults, idx) =>
        StandardDeploys.revGenerator(
          batchVaults,
          timestamp = 1565818101792L + idx,
          isLastBatch = 1 + idx == vaultBatches.size,
          shardId
        )
    }

    // Order of deploys is important for Registry to work correctly
    // - dependencies must be defined first in the list
    StandardDeploys.registryGenerator(registry, shardId) +:
      StandardDeploys.listOps(shardId) +:
      StandardDeploys.either(shardId) +:
      StandardDeploys.nonNegativeNumber(shardId) +:
      StandardDeploys.makeMint(shardId) +:
      StandardDeploys.authKey(shardId) +:
      StandardDeploys.revVault(shardId) +:
      StandardDeploys.multiSigRevVault(shardId) +:
      vaultDeploys :+
      StandardDeploys.poSGenerator(posParams, shardId)
  }

  def createGenesisBlock[F[_]: Concurrent: RuntimeManager](
      validator: ValidatorIdentity,
      genesis: Genesis
  ): F[BlockMessage] = {
    val blessedTerms =
      defaultBlessedTerms(genesis.proofOfStake, genesis.registry, genesis.vaults, genesis.shardId)

    RuntimeManager[F]
      .computeGenesis(blessedTerms, genesis.blockNumber, genesis.sender, genesis.shardId)
      .map {
        case (startHash, stateHash, processedDeploys) =>
          val unsignedBlock =
            createBlockWithProcessedDeploys(genesis, startHash, stateHash, processedDeploys)
          // Sign a block (hash should not be changed)
          val signedBlock = validator.signBlock(unsignedBlock)

          // This check is temporary until signing function will re-hash the block
          val unsignedHash = PrettyPrinter.buildString(unsignedBlock.blockHash)
          val signedHash   = PrettyPrinter.buildString(signedBlock.blockHash)
          assert(
            unsignedBlock.blockHash == signedBlock.blockHash,
            s"Signed block has different block hash unsigned: $unsignedHash, signed: $signedHash."
          )

          // Return signed genesis block
          signedBlock
      }
  }

  private def createBlockWithProcessedDeploys(
      genesis: Genesis,
      preStateHash: StateHash,
      postStateHash: StateHash,
      processedDeploys: Seq[ProcessedDeploy]
  ): BlockMessage = {
    // Ensure that all deploys are successfully executed
    assert(processedDeploys.forall(!_.isFailed), s"Genesis block contains failed deploys.")

    val state   = RholangState(deploys = processedDeploys.toList, systemDeploys = List.empty)
    val version = BlockVersion.Current
    val seqNum  = 0L

    // Return unsigned block with calculated hash
    unsignedBlockProto(
      version,
      genesis.shardId,
      genesis.blockNumber,
      genesis.sender,
      seqNum,
      preStateHash = preStateHash,
      postStateHash = postStateHash,
      justifications = List.empty,
      bonds = buildBondsMap(genesis.proofOfStake),
      rejectedDeploys = List.empty,
      state = state
    )
  }

  private def buildBondsMap(proofOfStake: ProofOfStake): Map[StateHash, Long] = {
    val bonds = proofOfStake.validators.flatMap(Validator.unapply).toMap
    bonds.map {
      case (pk, stake) =>
        val validator = ByteString.copyFrom(pk.bytes)
        (validator, stake)
    }
  }
}
