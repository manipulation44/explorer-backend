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

package org.alephium.explorer.service

import java.math.BigInteger
import java.net.InetAddress

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

import akka.http.scaladsl.model.Uri
import akka.util.ByteString
import sttp.client3._

import org.alephium.api
import org.alephium.api.Endpoints
import org.alephium.api.model.{ChainInfo, HashesAtHeight, SelfClique}
import org.alephium.explorer.Hash
import org.alephium.explorer.api.model._
import org.alephium.explorer.persistence.model._
import org.alephium.http.EndpointSender
import org.alephium.protocol
import org.alephium.protocol.config.GroupConfig
import org.alephium.protocol.mining.HashRate
import org.alephium.protocol.model.{Hint, Target}
import org.alephium.protocol.vm.LockupScript
import org.alephium.util.{Duration, Hex, TimeStamp}

trait BlockFlowClient {
  def fetchBlock(fromGroup: GroupIndex, hash: BlockEntry.Hash): Future[Either[String, BlockEntity]]

  def fetchChainInfo(fromGroup: GroupIndex, toGroup: GroupIndex): Future[Either[String, ChainInfo]]

  def fetchHashesAtHeight(fromGroup: GroupIndex,
                          toGroup: GroupIndex,
                          height: Height): Future[Either[String, HashesAtHeight]]

  def fetchBlocks(fromTs: TimeStamp,
                  toTs: TimeStamp,
                  uri: Uri): Future[Either[String, Seq[Seq[BlockEntity]]]]

  def fetchBlocksAtHeight(fromGroup: GroupIndex, toGroup: GroupIndex, height: Height)(
      implicit executionContext: ExecutionContext): Future[Either[Seq[String], Seq[BlockEntity]]] =
    fetchHashesAtHeight(fromGroup, toGroup, height).flatMap {
      case Right(hashesAtHeight) =>
        Future
          .sequence(
            hashesAtHeight.headers
              .map(hash => fetchBlock(fromGroup, new BlockEntry.Hash(hash)))
              .toSeq)
          .map { blocksEither =>
            val (errors, blocks) = blocksEither.partitionMap(identity)
            if (errors.nonEmpty) {
              Left(errors)
            } else {
              Right(blocks)
            }
          }
      case Left(error) => Future.successful(Left(Seq(error)))
    }

  def fetchSelfClique(): Future[Either[String, SelfClique]]

  def fetchUnconfirmedTransactions(uri: Uri): Future[Either[String, Seq[UnconfirmedTx]]]
}

object BlockFlowClient {
  def apply(uri: Uri,
            groupNum: Int,
            blockflowFetchMaxAge: Duration,
            maybeApiKey: Option[api.model.ApiKey])(
      implicit executionContext: ExecutionContext
  ): BlockFlowClient =
    new Impl(uri, groupNum, blockflowFetchMaxAge, maybeApiKey)

  private class Impl(uri: Uri,
                     groupNum: Int,
                     val blockflowFetchMaxAge: Duration,
                     val maybeApiKey: Option[api.model.ApiKey])(
      implicit executionContext: ExecutionContext
  ) extends BlockFlowClient
      with Endpoints
      with EndpointSender {

    implicit lazy val groupConfig: GroupConfig = new GroupConfig { val groups = groupNum }

    private implicit def groupIndexConversion(x: GroupIndex): protocol.model.GroupIndex =
      protocol.model.GroupIndex.unsafe(x.value)

    private def _send[A, B](
        endpoint: BaseEndpoint[A, B],
        uri: Uri,
        a: A
    ): Future[Either[String, B]] =
      send(endpoint, a, uri"${uri.toString}")
        .map(_.left.map(_.detail))

    @SuppressWarnings(Array("org.wartremover.warts.ToString"))
    //TODO Introduce monad transformer helper for more readability
    def fetchBlock(fromGroup: GroupIndex,
                   hash: BlockEntry.Hash): Future[Either[String, BlockEntity]] =
      fetchSelfClique().flatMap {
        case Left(error) => Future.successful(Left(error))
        case Right(selfClique) =>
          selfCliqueIndex(selfClique, fromGroup) match {
            case Left(error) => Future.successful(Left(error))
            case Right((nodeAddress, restPort)) =>
              val uri = s"http://${nodeAddress.getHostAddress}:${restPort}"
              _send(getBlock, uri, hash.value).map(_.map(blockProtocolToEntity))
          }
      }

    def fetchChainInfo(fromGroup: GroupIndex,
                       toGroup: GroupIndex): Future[Either[String, ChainInfo]] = {
      _send(getChainInfo, uri, protocol.model.ChainIndex(fromGroup, toGroup))
    }

    def fetchHashesAtHeight(fromGroup: GroupIndex,
                            toGroup: GroupIndex,
                            height: Height): Future[Either[String, HashesAtHeight]] =
      _send(getHashesAtHeight, uri, (protocol.model.ChainIndex(fromGroup, toGroup), height.value))

    def fetchBlocks(fromTs: TimeStamp,
                    toTs: TimeStamp,
                    uri: Uri): Future[Either[String, Seq[Seq[BlockEntity]]]] = {
      _send(getBlockflow, uri, api.model.TimeInterval(fromTs, toTs))
        .map(_.map(_.blocks.map(_.map(blockProtocolToEntity).toSeq).toSeq))
    }

    def fetchUnconfirmedTransactions(uri: Uri): Future[Either[String, Seq[UnconfirmedTx]]] =
      _send(listUnconfirmedTransactions, uri, ())
        .map(_.map { utxs =>
          utxs.flatMap { utx =>
            utx.unconfirmedTransactions.map { tx =>
              val inputs  = tx.unsigned.inputs.map(inputToUInput).toSeq
              val outputs = tx.unsigned.outputs.map(outputToUOutput).toSeq
              txToUTx(tx, utx.fromGroup, utx.toGroup, inputs, outputs)
            }
          }.toSeq
        })

    def fetchSelfClique(): Future[Either[String, SelfClique]] =
      _send(getSelfClique, uri, ())

    private def selfCliqueIndex(selfClique: SelfClique,
                                group: GroupIndex): Either[String, (InetAddress, Int)] = {
      if (selfClique.groupNumPerBroker <= 0) {
        Left(
          s"SelfClique.groupNumPerBroker ($selfClique.groupNumPerBroker) cannot be less or equal to zero")
      } else {
        Right(selfClique.peer(group)).map(node => (node.address, node.restPort))
      }
    }
  }

  def blockProtocolToInputEntities(block: api.model.BlockEntry): Seq[InputEntity] = {
    val hash         = new BlockEntry.Hash(block.hash)
    val mainChain    = false
    val transactions = block.transactions.toSeq.zipWithIndex
    val inputs =
      transactions.flatMap {
        case (tx, txIndex) =>
          tx.unsigned.inputs.toSeq.zipWithIndex.map {
            case (in, index) =>
              inputToEntity(in, hash, tx.unsigned.hash, block.timestamp, mainChain, index, txIndex)
          }
      }
    val contractInputs =
      transactions.flatMap {
        case (tx, txIndex) =>
          tx.contractInputs.toSeq.zipWithIndex.map {
            case (outputRef, index) =>
              val shiftIndex = index + tx.unsigned.inputs.length
              outputRefToInputEntity(outputRef,
                                     hash,
                                     tx.unsigned.hash,
                                     block.timestamp,
                                     mainChain,
                                     shiftIndex,
                                     txIndex)
          }
      }
    inputs ++ contractInputs
  }

  def blockProtocolToOutputEntities(block: api.model.BlockEntry): Seq[OutputEntity] = {
    val hash         = new BlockEntry.Hash(block.hash)
    val mainChain    = false
    val transactions = block.transactions.toSeq.zipWithIndex
    val outputs =
      transactions.flatMap {
        case (tx, txIndex) =>
          tx.unsigned.outputs.toSeq.zipWithIndex.map {
            case (out, index) =>
              outputToEntity(out,
                             hash,
                             tx.unsigned.hash,
                             index,
                             block.timestamp,
                             mainChain,
                             txIndex)
          }
      }
    val generatedOutputs =
      transactions.flatMap {
        case (tx, txIndex) =>
          tx.generatedOutputs.toSeq.zipWithIndex.map {
            case (out, index) =>
              val shiftIndex = index + tx.unsigned.outputs.length
              outputToEntity(out,
                             hash,
                             tx.unsigned.hash,
                             shiftIndex,
                             block.timestamp,
                             mainChain,
                             txIndex)
          }
      }
    outputs ++ generatedOutputs
  }
  def blockProtocolToEntity(block: api.model.BlockEntry)(
      implicit groupConfig: GroupConfig): BlockEntity = {
    val hash         = new BlockEntry.Hash(block.hash)
    val mainChain    = false
    val transactions = block.transactions.toSeq.zipWithIndex
    val chainFrom    = block.chainFrom
    val chainTo      = block.chainTo
    val inputs       = blockProtocolToInputEntities(block)
    val outputs      = blockProtocolToOutputEntities(block)
    BlockEntity(
      hash,
      block.timestamp,
      GroupIndex.unsafe(block.chainFrom),
      GroupIndex.unsafe(block.chainTo),
      Height.unsafe(block.height),
      block.deps.map(new BlockEntry.Hash(_)).toSeq,
      transactions.map {
        case (tx, index) =>
          txToEntity(tx, hash, block.timestamp, index, mainChain, chainFrom, chainTo)
      },
      inputs,
      outputs,
      mainChain = mainChain,
      block.nonce,
      block.version,
      block.depStateHash,
      block.txsHash,
      block.target,
      computeHashRate(block.target)
    )
  }

  private def txToUTx(tx: api.model.TransactionTemplate,
                      chainFrom: Int,
                      chainTo: Int,
                      inputs: Seq[UInput],
                      outputs: Seq[UOutput]): UnconfirmedTx =
    UnconfirmedTx(
      new Transaction.Hash(tx.unsigned.hash),
      GroupIndex.unsafe(chainFrom),
      GroupIndex.unsafe(chainTo),
      inputs,
      outputs,
      tx.unsigned.gasAmount,
      tx.unsigned.gasPrice
    )

  private def txToEntity(tx: api.model.Transaction,
                         blockHash: BlockEntry.Hash,
                         timestamp: TimeStamp,
                         index: Int,
                         mainChain: Boolean,
                         chainFrom: Int,
                         chainTo: Int): TransactionEntity =
    TransactionEntity(
      new Transaction.Hash(tx.unsigned.hash),
      blockHash,
      timestamp,
      GroupIndex.unsafe(chainFrom),
      GroupIndex.unsafe(chainTo),
      tx.unsigned.gasAmount,
      tx.unsigned.gasPrice,
      index,
      mainChain
    )

  private def inputToUInput(input: api.model.Input): UInput = {
    val unlockScript = input match {
      case asset: api.model.Input.Asset => Some(Hex.toHexString(asset.unlockScript))
      case _: api.model.Input.Contract  => None
    }
    UInput(
      OutputRef(input.outputRef.hint, input.outputRef.key),
      unlockScript
    )
  }

  private def inputToEntity(input: api.model.Input,
                            blockHash: BlockEntry.Hash,
                            txId: Hash,
                            timestamp: TimeStamp,
                            mainChain: Boolean,
                            index: Int,
                            txIndex: Int): InputEntity = {
    val unlockScript = input match {
      case asset: api.model.Input.Asset => Some(Hex.toHexString(asset.unlockScript))
      case _: api.model.Input.Contract  => None
    }
    InputEntity(blockHash,
                new Transaction.Hash(txId),
                timestamp,
                input.outputRef.hint,
                input.outputRef.key,
                unlockScript,
                mainChain,
                index,
                txIndex)
  }

  private def outputRefToInputEntity(outputRef: api.model.OutputRef,
                                     blockHash: BlockEntry.Hash,
                                     txId: Hash,
                                     timestamp: TimeStamp,
                                     mainChain: Boolean,
                                     index: Int,
                                     txIndex: Int): InputEntity = {
    InputEntity(
      blockHash,
      new Transaction.Hash(txId),
      timestamp,
      outputRef.hint,
      outputRef.key,
      None,
      mainChain,
      index,
      txIndex
    )
  }

  private def outputToUOutput(output: api.model.Output): UOutput = {
    val lockTime = output match {
      case asset: api.model.Output.Asset if asset.lockTime.millis > 0 => Some(asset.lockTime)
      case _                                                          => None
    }
    UOutput(
      output.alphAmount.value,
      new Address(output.address.toBase58),
      lockTime
    )
  }

  private def outputToEntity(output: api.model.Output,
                             blockHash: BlockEntry.Hash,
                             txId: Hash,
                             index: Int,
                             timestamp: TimeStamp,
                             mainChain: Boolean,
                             txIndex: Int): OutputEntity = {
    val lockTime = output match {
      case asset: api.model.Output.Asset if asset.lockTime.millis > 0 => Some(asset.lockTime)
      case _                                                          => None
    }
    val hint = output.address.lockupScript match {
      case asset: LockupScript.Asset  => Hint.ofAsset(asset.scriptHint)
      case contract: LockupScript.P2C => Hint.ofContract(contract.scriptHint)
    }
    OutputEntity(
      blockHash,
      new Transaction.Hash(txId),
      timestamp,
      hint.value,
      protocol.model.TxOutputRef.key(txId, index),
      output.alphAmount.value,
      new Address(output.address.toBase58),
      mainChain,
      lockTime,
      index,
      txIndex
    )
  }
  // scalastyle:off magic.number
  def computeHashRate(targetBytes: ByteString)(implicit groupConfig: GroupConfig): BigInteger = {
    val target          = Target.unsafe(targetBytes)
    val blockTargetTime = Duration.ofSecondsUnsafe(64) //TODO add this to config
    HashRate.from(target, blockTargetTime).value
  }
  // scalastyle:on magic.number
}
