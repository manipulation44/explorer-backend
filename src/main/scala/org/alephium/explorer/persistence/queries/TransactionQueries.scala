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

package org.alephium.explorer.persistence.queries

import scala.concurrent.ExecutionContext

import com.typesafe.scalalogging.StrictLogging
import slick.basic.DatabaseConfig
import slick.dbio.DBIOAction
import slick.jdbc.JdbcProfile

import org.alephium.explorer.Hash
import org.alephium.explorer.api.model._
import org.alephium.explorer.persistence.{DBActionR, DBActionW}
import org.alephium.explorer.persistence.model._
import org.alephium.explorer.persistence.schema.{InputSchema, OutputSchema, TransactionSchema}
import org.alephium.util.TimeStamp

trait TransactionQueries
    extends TransactionSchema
    with InputSchema
    with OutputSchema
    with StrictLogging {

  implicit def executionContext: ExecutionContext
  val config: DatabaseConfig[JdbcProfile]
  import config.profile.api._

  def insertTransactionFromBlockQuery(blockEntity: BlockEntity): DBActionW[Unit] = {
    for {
      _ <- DBIOAction.sequence(blockEntity.transactions.map(transactionsTable.insertOrUpdate))
      _ <- DBIOAction.sequence(blockEntity.inputs.map(inputsTable.insertOrUpdate))
      _ <- DBIOAction.sequence(blockEntity.outputs.map(outputsTable.insertOrUpdate))
    } yield ()
  }

  private val listTransactionsQuery = Compiled { blockHash: Rep[BlockEntry.Hash] =>
    transactionsTable
      .filter(_.blockHash === blockHash)
      .sortBy(_.timestamp.asc)
      .map(tx => (tx.hash, tx.timestamp))
  }

  def listTransactionsAction(blockHash: BlockEntry.Hash): DBActionR[Seq[Transaction]] =
    for {
      txEntities <- listTransactionsQuery(blockHash).result
      txs <- DBIOAction.sequence(
        txEntities.map(pair => getKnownTransactionAction(pair._1, pair._2)))
    } yield txs

  private val countTransactionsQuery = Compiled { blockHash: Rep[BlockEntry.Hash] =>
    transactionsTable.filter(_.blockHash === blockHash).length
  }

  def countTransactionsAction(blockHash: BlockEntry.Hash): DBActionR[Int] =
    countTransactionsQuery(blockHash).result

  private val getTimestampQuery = Compiled { txHash: Rep[Transaction.Hash] =>
    transactionsTable.filter(_.hash === txHash).map(_.timestamp)
  }

  def getTransactionAction(txHash: Transaction.Hash): DBActionR[Option[Transaction]] =
    getTimestampQuery(txHash).result.headOption.flatMap {
      case None            => DBIOAction.successful(None)
      case Some(timestamp) => getKnownTransactionAction(txHash, timestamp).map(Some.apply)
    }

  private val mainInputs  = inputsTable.filter(_.mainChain)
  private val mainOutputs = outputsTable.filter(_.mainChain)

  private val getTxHashesQuery = Compiled { (address: Rep[Address], txLimit: ConstColumn[Long]) =>
    mainOutputs
      .filter(_.address === address)
      .map(out => (out.txHash, out.timestamp))
      .distinct
      .sortBy { case (_, timestamp) => timestamp.asc }
      .take(txLimit)
  }

  private val inputsFromTxs = Compiled { (address: Rep[Address], txLimit: ConstColumn[Long]) =>
    mainOutputs
      .filter(_.address === address)
      .map(out => (out.txHash, out.timestamp))
      .distinct
      .sortBy { case (_, timestamp) => timestamp.asc }
      .take(txLimit)
      .join(mainInputs)
      .on(_._1 === _.txHash)
      .joinLeft(mainOutputs)
      .on {
        case ((_, input), outputs) =>
          input.outputRefKey === outputs.key
      }
      .map {
        case (((txHash, _), input), outputOpt) =>
          (txHash,
           (input.scriptHint,
            input.outputRefKey,
            input.unlockScript,
            outputOpt.map(_.txHash),
            outputOpt.map(_.address),
            outputOpt.map(_.amount)))
      }
  }

  private val outputsFromTxs = Compiled { (address: Rep[Address], txLimit: ConstColumn[Long]) =>
    mainOutputs
      .filter(_.address === address)
      .map(out => (out.txHash, out.timestamp))
      .distinct
      .sortBy { case (_, timestamp) => timestamp.asc }
      .take(txLimit)
      .join(mainOutputs)
      .on(_._1 === _.txHash)
      .joinLeft(mainInputs)
      .on {
        case ((_, out), inputs) =>
          out.key === inputs.outputRefKey
      }
      .map {
        case (((txHash, _), output), input) =>
          (txHash, (output.amount, output.address, input.map(_.txHash)))
      }
  }

  def getTransactionsByAddress(address: Address, txLimit: Long): DBActionR[Seq[Transaction]] = {
    val txHashesTsQuery = getTxHashesQuery(address -> txLimit)
    for {
      txHashesTs <- txHashesTsQuery.result
      ins        <- inputsFromTxs(address -> txLimit).result
      ous        <- outputsFromTxs(address -> txLimit).result
    } yield {
      val insByTx = ins.groupBy(_._1).view.mapValues(_.map { case (_, in) => toApiInput(in) })
      val ousByTx = ous.groupBy(_._1).view.mapValues(_.map { case (_, o)  => toApiOutput(o) })
      txHashesTs.map {
        case (tx, ts) =>
          val ins = insByTx.getOrElse(tx, Seq.empty)
          val ous = ousByTx.getOrElse(tx, Seq.empty)
          Transaction(tx, ts, ins, ous)
      }
    }
  }

  private val getInputsQuery = Compiled { (txHash: Rep[Transaction.Hash]) =>
    mainInputs
      .filter(_.txHash === txHash)
      .joinLeft(mainOutputs)
      .on(_.outputRefKey === _.key)
      .map {
        case (input, outputOpt) =>
          (input.scriptHint,
           input.outputRefKey,
           input.unlockScript,
           outputOpt.map(_.txHash),
           outputOpt.map(_.address),
           outputOpt.map(_.amount))
      }
  }

  private val getOutputsQuery = Compiled { (txHash: Rep[Transaction.Hash]) =>
    outputsTable
      .filter(output => output.mainChain && output.txHash === txHash)
      .map(output => (output.key, output.address, output.amount))
      .joinLeft(inputsTable)
      .on(_._1 === _.outputRefKey)
      .map { case (output, input) => (output._3, output._2, input.map(_.txHash)) }
  }

  private def getKnownTransactionAction(txHash: Transaction.Hash,
                                        timestamp: TimeStamp): DBActionR[Transaction] =
    for {
      ins  <- getInputsQuery(txHash).result
      outs <- getOutputsQuery(txHash).result
    } yield {
      Transaction(txHash, timestamp, ins.map(toApiInput), outs.map(toApiOutput))
    }

  private val getBalanceQuery = Compiled { address: Rep[Address] =>
    outputsTable
      .filter(output => output.mainChain && output.address === address)
      .map(output => (output.key, output.amount))
      .joinLeft(inputsTable.filter(_.mainChain))
      .on(_._1 === _.outputRefKey)
      .filter(_._2.isEmpty)
      .map(_._1._2)
      .sum
  }

  def getBalanceAction(address: Address): DBActionR[Double] =
    getBalanceQuery(address).result.map(_.getOrElse(0.0))

  private val toApiInput = {
    (scriptHint: Int,
     key: Hash,
     unlockScript: Option[String],
     txHashOpt: Option[Transaction.Hash],
     addressOpt: Option[Address],
     amountOpt: Option[Double]) =>
      Input(Output.Ref(scriptHint, key), unlockScript, txHashOpt, addressOpt, amountOpt)
  }.tupled

  private val toApiOutput = (Output.apply _).tupled

  // switch logger.trace when we can disable debugging mode
  protected def debugShow(query: slickProfile.ProfileAction[_, _, _]) = {
    print(s"${query.statements.mkString}\n")
  }
}
