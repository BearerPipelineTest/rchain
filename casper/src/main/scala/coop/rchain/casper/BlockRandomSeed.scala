package coop.rchain.casper

import coop.rchain.crypto.PublicKey
import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.models.syntax._
import coop.rchain.rspace.hashing.Blake2b256Hash
import scodec.bits.ByteVector
import scodec.codecs.{bytes, uint8, ulong, utf8, variableSizeBytes}
import scodec.{Codec, TransformSyntax}

final case class BlockRandomSeed private (
    shardId: String,
    blockNumber: Long,
    sender: PublicKey,
    preStateHash: Blake2b256Hash
)

object BlockRandomSeed {
  def apply(
      shardId: String,
      blockNumber: Long,
      sender: PublicKey,
      preStateHash: Blake2b256Hash
  ): BlockRandomSeed = {
    assert(shardId.onlyAscii, "Shard name should contain only ASCII characters")
    new BlockRandomSeed(shardId, blockNumber, sender, preStateHash)
  }

  private val codecPublicKey: Codec[PublicKey] = variableSizeBytes(uint8, bytes)
    .xmap[PublicKey](bv => PublicKey(bv.toArray), pk => ByteVector(pk.bytes))

  val codecBlockRandomSeed: Codec[BlockRandomSeed] =
    (variableSizeBytes(uint8, utf8) :: ulong(bits = 63) ::
      codecPublicKey :: Blake2b256Hash.codecBlake2b256Hash).as[BlockRandomSeed]

  private def encode(blockRandomSeed: BlockRandomSeed): Array[Byte] =
    codecBlockRandomSeed.encode(blockRandomSeed).require.toByteArray

  def generateRandomNumber(blockRandomSeed: BlockRandomSeed): Blake2b512Random =
    Blake2b512Random(encode(blockRandomSeed))
}
