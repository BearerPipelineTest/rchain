package coop.rchain.blockstorage.dag

import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.approvedStore.{approvedBlockToBytes, bytesToApprovedBlock}
import coop.rchain.blockstorage.blockStore.{blockMessageToBytes, bytesToBlockMessage}
import coop.rchain.casper.protocol.{ApprovedBlock, BlockMessage, DeployData, DeployDataProto}
import coop.rchain.crypto.signatures.Signed
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.{BlockHash, BlockMetadata, Validator}
import scodec.bits.ByteVector
import scodec.codecs._
import scodec.{Attempt, Codec, Err}

object codecs {
  private def xmapToByteString(codec: Codec[ByteVector]): Codec[ByteString] =
    codec.xmap[ByteString](
      byteVector => ByteString.copyFrom(byteVector.toArray),
      byteString => ByteVector(byteString.toByteArray)
    )

  val codecByteString = xmapToByteString(bytes)

  val codecBlockHash = xmapToByteString(bytes(BlockHash.Length))

  val codecBlockMetadata = bytes.xmap[BlockMetadata](
    byteVector => BlockMetadata.fromBytes(byteVector.toArray),
    blockMetadata => ByteVector(blockMetadata.toByteString.toByteArray)
  )

  val codecBlockMessage = bytes.exmap[BlockMessage](
    byteVector => Attempt.fromEither(bytesToBlockMessage(byteVector.toArray).leftMap(Err(_))),
    blockMessage => Attempt.successful(ByteVector(blockMessageToBytes(blockMessage)))
  )

  val codecApprovedBlock = bytes.exmap[ApprovedBlock](
    byteVector => Attempt.fromEither(bytesToApprovedBlock(byteVector.toArray).leftMap(Err(_))),
    approvedBlock => Attempt.successful(ByteVector(approvedBlockToBytes(approvedBlock)))
  )

  val codecValidator = xmapToByteString(bytes(Validator.Length))

  val codecSeqNum = int32

  val codecBlockHashSet = listOfN(int32, codecBlockHash).xmap[Set[BlockHash]](_.toSet, _.toList)

  val codecSignedDeployData = bytes.xmap[Signed[DeployData]](
    byteVector => DeployData.from(DeployDataProto.parseFrom(byteVector.toArray)).right.get,
    signedDeployData => ByteVector(DeployData.toProto(signedDeployData).toByteArray)
  )
}
