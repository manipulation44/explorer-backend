// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.explorer.persistence.model

import java.math.BigInteger

import akka.util.ByteString

import org.alephium.explorer.Hash
import org.alephium.explorer.api.model.{BlockEntry, GroupIndex, Height, Transaction}
import org.alephium.util.TimeStamp

final case class BlockHeader(
    hash: BlockEntry.Hash,
    timestamp: Long,
    chainFrom: GroupIndex,
    chainTo: GroupIndex,
    height: Height,
    mainChain: Boolean,
    nonce: ByteString,
    version: Byte,
    depStateHash: Hash,
    txsHash: Hash,
    target: ByteString,
    hashrate: BigInteger
) {
  def toApi(deps: Seq[BlockEntry.Hash], transactions: Seq[Transaction]): BlockEntry =
    BlockEntry(hash,
               TimeStamp.unsafe(timestamp),
               chainFrom,
               chainTo,
               height,
               deps,
               transactions,
               mainChain,
               hashrate)

  def toLiteApi(txNumber: Int): BlockEntry.Lite =
    BlockEntry.Lite(hash,
                    TimeStamp.unsafe(timestamp),
                    chainFrom,
                    chainTo,
                    height,
                    txNumber,
                    mainChain,
                    hashrate)
}

object BlockHeader {
  def fromEntity(blockEntity: BlockEntity): BlockHeader =
    BlockHeader(
      blockEntity.hash,
      blockEntity.timestamp.millis,
      blockEntity.chainFrom,
      blockEntity.chainTo,
      blockEntity.height,
      blockEntity.mainChain,
      blockEntity.nonce,
      blockEntity.version,
      blockEntity.depStateHash,
      blockEntity.txsHash,
      blockEntity.target,
      blockEntity.hashrate
    )
}