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

import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext

import com.typesafe.scalalogging.StrictLogging
import slick.jdbc.PostgresProfile.api._

import org.alephium.explorer.api.model._
import org.alephium.explorer.persistence._
import org.alephium.explorer.persistence.queries.result.TxByTokenQR
import org.alephium.explorer.persistence.schema.CustomGetResult._
import org.alephium.explorer.persistence.schema.CustomSetParameter._
import org.alephium.explorer.util.SlickUtil._
import org.alephium.protocol.model.{Address, TokenId}
import org.alephium.util.{TimeStamp, U256}

object TokenQueries extends StrictLogging {

  def getTokenBalanceAction(address: Address, token: TokenId)(
      implicit ec: ExecutionContext): DBActionR[(U256, U256)] =
    getTokenBalanceUntilLockTime(
      address = address,
      token,
      lockTime = TimeStamp.now()
    ) map {
      case (total, locked) =>
        (total.getOrElse(U256.Zero), locked.getOrElse(U256.Zero))
    }

  def getTokenBalanceUntilLockTime(address: Address, token: TokenId, lockTime: TimeStamp)(
      implicit ec: ExecutionContext): DBActionR[(Option[U256], Option[U256])] =
    sql"""
      SELECT sum(token_outputs.amount),
             sum(CASE
                     WHEN token_outputs.lock_time is NULL or token_outputs.lock_time < ${lockTime.millis} THEN 0
                     ELSE token_outputs.amount
                 END)
      FROM token_outputs
               LEFT JOIN inputs
                         ON token_outputs.key = inputs.output_ref_key
                             AND inputs.main_chain = true
      WHERE token_outputs.spent_finalized IS NULL
        AND token_outputs.address = $address
        AND token_outputs.token = $token
        AND token_outputs.main_chain = true
        AND inputs.block_hash IS NULL;
    """.asAS[(Option[U256], Option[U256])].exactlyOne

  def listTokensAction(pagination: Pagination): DBActionSR[TokenId] = {
    sql"""
      SELECT token
      FROM token_info
      ORDER BY last_used DESC
    """
      .paginate(pagination)
      .asAS[TokenId]

  }

  def getTransactionsByToken(token: TokenId, pagination: Pagination)(
      implicit ec: ExecutionContext): DBActionR[ArraySeq[Transaction]] = {
    for {
      txHashesTs <- listTokenTransactionsAction(token, pagination)
      txs        <- TransactionQueries.getTransactions(txHashesTs.map(_.toTxByAddressQR))
    } yield txs
  }

  def getAddressesByToken(token: TokenId, pagination: Pagination): DBActionR[ArraySeq[Address]] = {
    sql"""
      SELECT DISTINCT address
      FROM token_tx_per_addresses
      WHERE token = $token
    """
      .paginate(pagination)
      .asAS[Address]
  }

  def listTokenTransactionsAction(token: TokenId,
                                  pagination: Pagination): DBActionSR[TxByTokenQR] = {
    sql"""
      SELECT #${TxByTokenQR.selectFields}
      FROM transaction_per_token
      WHERE main_chain = true
      AND token = $token
      ORDER BY block_timestamp DESC, tx_order
    """
      .paginate(pagination)
      .asAS[TxByTokenQR]
  }

  def listAddressTokensAction(address: Address, pagination: Pagination): DBActionSR[TokenId] =
    sql"""
      SELECT DISTINCT token
      FROM token_tx_per_addresses
      WHERE address = $address
      AND main_chain = true
    """
      .paginate(pagination)
      .asAS[TokenId]

  def getTokenTransactionsByAddress(address: Address, token: TokenId, pagination: Pagination)(
      implicit ec: ExecutionContext): DBActionR[ArraySeq[Transaction]] = {
    for {
      txHashesTs <- getTokenTxHashesByAddressQuery(address, token, pagination)
      txs        <- TransactionQueries.getTransactions(txHashesTs.map(_.toTxByAddressQR))
    } yield txs
  }

  def getTokenTxHashesByAddressQuery(address: Address,
                                     token: TokenId,
                                     pagination: Pagination): DBActionSR[TxByTokenQR] = {
    sql"""
      SELECT #${TxByTokenQR.selectFields}
      FROM token_tx_per_addresses
      WHERE main_chain = true
      AND address = $address
      AND token = $token
      ORDER BY block_timestamp DESC, tx_order
    """
      .paginate(pagination)
      .asAS[TxByTokenQR]
  }
}
