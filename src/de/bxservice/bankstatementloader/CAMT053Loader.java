/***********************************************************************
 * This file is part of iDempiere ERP Open Source                      *
 * http://www.idempiere.org                                            *
 *                                                                     *
 * Copyright (C) Contributors                                          *
 *                                                                     *
 * This program is free software; you can redistribute it and/or       *
 * modify it under the terms of the GNU General Public License         *
 * as published by the Free Software Foundation; either version 2      *
 * of the License, or (at your option) any later version.              *
 *                                                                     *
 * This program is distributed in the hope that it will be useful,     *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of      *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the        *
 * GNU General Public License for more details.                        *
 *                                                                     *
 * You should have received a copy of the GNU General Public License   *
 * along with this program; if not, write to the Free Software         *
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,          *
 * MA 02110-1301, USA.                                                 *
 *                                                                     *
 * Contributors:                                                       *
 * - Carlos Ruiz - globalqss - BX Service                              *
 **********************************************************************/

package de.bxservice.bankstatementloader;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.impexp.BankStatementLoaderInterface;
import org.compiere.model.MBankStatementLoader;
import org.compiere.util.CLogger;

import net.tjeerd.camt053parser.Camt053Parser;
import net.tjeerd.camt053parser.model.AccountStatement2;
import net.tjeerd.camt053parser.model.BalanceType12Code;
import net.tjeerd.camt053parser.model.CashBalance3;
import net.tjeerd.camt053parser.model.CreditDebitCode;
import net.tjeerd.camt053parser.model.Document;
import net.tjeerd.camt053parser.model.EntryDetails1;
import net.tjeerd.camt053parser.model.EntryTransaction2;
import net.tjeerd.camt053parser.model.ReportEntry2;

public class CAMT053Loader implements BankStatementLoaderInterface {

	private MBankStatementLoader	m_bsl;
	private StringBuffer			m_errorMessage;
	private StringBuffer			m_errorDescription;
	private StatementLine			m_line;

	/**	Static Logger	*/
	private static CLogger	s_log	= CLogger.getCLogger (CAMT053Loader.class);

	@Override
	public boolean init(MBankStatementLoader bsl) {
		if (bsl == null) {
			m_errorMessage = new StringBuffer("ErrorInitializingParser");
			m_errorDescription = new StringBuffer("ImportController is a null reference");
			return false;
		}
		this.m_bsl = bsl;
		return true;
	}

	@Override
	public boolean isValid() {
		return true;
	}

	@Override
	public boolean loadLines() {
        Camt053Parser camt053Parser = new Camt053Parser();

        try {
            FileInputStream fileInputStream = new FileInputStream(new File(m_bsl.getLocalFileName()));
            Document camt053Document = camt053Parser.parse(fileInputStream);

            // Get all statements
            for (AccountStatement2 stmt : camt053Document.getBkToCstmrStmt().getStmt()) {
            	Timestamp statementDate = null;
            	if (stmt.getFrToDt() != null) {
            		statementDate = new Timestamp(stmt.getFrToDt().getToDtTm().toGregorianCalendar().getTimeInMillis());
            	}
            	if (statementDate == null) {
            		// try to get the statement date from the opening balance
            		for (CashBalance3 bal : stmt.getBal()) {
            			if (BalanceType12Code.OPBD.equals(bal.getTp().getCdOrPrtry().getCd())) {
            				if (bal.getDt() != null) {
            					if (bal.getDt().getDt() != null) {
            						statementDate = new Timestamp(bal.getDt().getDt().toGregorianCalendar().getTimeInMillis());
            					} else if (bal.getDt().getDtTm() != null) {
            						statementDate = new Timestamp(bal.getDt().getDtTm().toGregorianCalendar().getTimeInMillis());
            					}
            				}
            			}
            		}
            	}
            	if (statementDate == null) {
            		s_log.warning("No statement date found");
            	}
                // Get entries
                for (ReportEntry2 ntry : stmt.getNtry()) {
                    // Get entry details
                    for (EntryDetails1 ntryDtl : ntry.getNtryDtls()) {
                    	// Get transactions
                    	for (EntryTransaction2 txDtl : ntryDtl.getTxDtls()) {
                    		m_line = new StatementLine();
                    		// from statement
                        	m_line.statementReference = stmt.getId();
                        	m_line.statementDate = statementDate;
                        	m_line.iban = stmt.getAcct().getId().getIBAN();
                        	// from entry
                        	m_line.trxType = ntry.getCdtDbtInd().name(); // DBIT | CRDT
                        	m_line.trxID = ntry.getNtryRef();
                        	m_line.statementLineDate = new Timestamp(ntry.getBookgDt().getDt().toGregorianCalendar().getTimeInMillis());
                        	m_line.valutaDate = new Timestamp(ntry.getValDt().getDt().toGregorianCalendar().getTimeInMillis());
                        	m_line.reference = ntry.getAcctSvcrRef();
                        	// from transaction
                            m_line.memo = txDtl.getAddtlTxInf();
                        	m_line.currency = txDtl.getAmtDtls().getTxAmt().getAmt().getCcy();
                        	m_line.stmtAmt = txDtl.getAmtDtls().getTxAmt().getAmt().getValue();
                        	if (CreditDebitCode.DBIT == ntry.getCdtDbtInd())
                            	m_line.stmtAmt = m_line.stmtAmt.negate();
                        	if (txDtl.getRefs() != null) {
                            	m_line.payeeAccountNo = txDtl.getRefs().getEndToEndId();
                            	m_line.payeeName = txDtl.getRefs().getMndtId();
                            	m_line.checkNo = txDtl.getRefs().getAcctSvcrRef();
                        	}
                        	// create the I_BankStatement record
                        	if (!m_bsl.saveLine())
	 							return false;
                    	}
                    }
                    // m_line.chargeName
                    // m_line.chargeAmt
                    // m_line.interestAmt
                    // m_line.routingNo
                    // m_line.bankAccountNo
                    // m_line.isReversal
                }
            }

        } catch (Exception e) {
            throw new AdempiereException(e);
        }
		return true;
	}

	@Override
	public String getLastErrorMessage() {
		return m_errorMessage.toString();
	}

	@Override
	public String getLastErrorDescription() {
		return m_errorDescription.toString();
	}

	@Override
	public Timestamp getDateLastRun() {
		return null;
	}

	@Override
	public String getRoutingNo() {
		return m_line.routingNo;
	}

	@Override
	public String getBankAccountNo() {
		return m_line.bankAccountNo;
	}

	@Override
	public String getIBAN() {
		return m_line.iban;
	}

	@Override
	public String getStatementReference() {
		return m_line.statementReference;
	}

	@Override
	public Timestamp getStatementDate() {
		return m_line.statementDate;
	}

	@Override
	public String getTrxID() {
		return m_line.trxID;
	}

	@Override
	public String getReference() {
		return m_line.reference;
	}

	@Override
	public String getCheckNo() {
		return m_line.checkNo;
	}

	@Override
	public String getPayeeName() {
		return m_line.payeeName;
	}

	@Override
	public String getPayeeAccountNo() {
		return m_line.payeeAccountNo;
	}

	@Override
	public Timestamp getStatementLineDate() {
		return m_line.statementLineDate;
	}

	@Override
	public Timestamp getValutaDate() {
		return m_line.valutaDate;
	}

	@Override
	public String getTrxType() {
		return m_line.trxType;
	}

	@Override
	public boolean getIsReversal() {
		return m_line.isReversal;
	}

	@Override
	public String getCurrency() {
		return m_line.currency;
	}

	@Override
	public BigDecimal getStmtAmt() {
		return m_line.stmtAmt;
	}

	@Override
	public BigDecimal getTrxAmt() {
		return m_line.stmtAmt;
	}

	@Override
	public BigDecimal getInterestAmt() {
		return m_line.interestAmt;
	}

	@Override
	public String getMemo() {
		return m_line.memo;
	}

	@Override
	public String getChargeName() {
		return m_line.chargeName;
	}

	@Override
	public BigDecimal getChargeAmt() {
		return m_line.chargeAmt;
	}

	static class StatementLine
	{
		protected String routingNo = null;
		protected String bankAccountNo = null;
		protected String statementReference = null;
		protected Timestamp statementDate = null;
		protected Timestamp statementLineDate = null;
		
		protected String reference = null;
		protected Timestamp valutaDate;
		protected String trxType = null; 
		protected boolean isReversal = false; 
		protected String currency = null;
		protected BigDecimal stmtAmt = null; 
		protected String memo = null; 
		protected String chargeName = null;
		protected BigDecimal chargeAmt = null;	
		protected String payeeAccountNo = null;
		protected String payeeName = null;
		protected String trxID = null;
		protected String checkNo = null;
		protected BigDecimal interestAmt = null;
		protected String iban = null;
	}

}
