package de.metas.procurement.webui.sync;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.IntegrationTest;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;

import de.metas.procurement.sync.IAgentSync;
import de.metas.procurement.sync.IServerSync;
import de.metas.procurement.sync.protocol.SyncBPartner;
import de.metas.procurement.sync.protocol.SyncBPartnersRequest;
import de.metas.procurement.sync.protocol.SyncContract;
import de.metas.procurement.sync.protocol.SyncContractLine;
import de.metas.procurement.sync.protocol.SyncProduct;
import de.metas.procurement.webui.Application;
import de.metas.procurement.webui.model.BPartner;
import de.metas.procurement.webui.model.ContractLine;
import de.metas.procurement.webui.model.Product;
import de.metas.procurement.webui.repository.BPartnerRepository;
import de.metas.procurement.webui.repository.ContractLineRepository;
import de.metas.procurement.webui.repository.ContractRepository;
import de.metas.procurement.webui.repository.ProductRepository;
import de.metas.procurement.webui.repository.ProductSupplyRepository;
import de.metas.procurement.webui.service.IProductSuppliesService;
import de.metas.procurement.webui.sync.AgentSyncIntegrationTest.AgentSyncTestConfig;
import de.metas.procurement.webui.util.DateUtils;
import de.metas.procurement.webui.util.DummyDataProducer;

/*
 * #%L
 * de.metas.procurement.webui
 * %%
 * Copyright (C) 2016 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@RunWith(SpringJUnit4ClassRunner.class)
@SpringApplicationConfiguration(classes = { AgentSyncTestConfig.class })
@WebAppConfiguration
@IntegrationTest("server.port:0")
public class AgentSyncIntegrationTest
{
	@Configuration
	@Import(Application.class)
	public static class AgentSyncTestConfig
	{
		@Bean
		public IServerSync serverSync()
		{
			return new NullServerSync();
		}
	}

	//
	// Services
	@Autowired
	IAgentSync agentSync;
	@Autowired
	DummyDataProducer dummyDataProducer;
	@Autowired
	IProductSuppliesService productSuppliesService;

	//
	// Repositories
	@Autowired
	BPartnerRepository bpartnerRepo;
	@Autowired
	ProductRepository productsRepo;
	@Autowired
	ContractRepository contractsRepo;
	@Autowired
	ContractLineRepository contractLinesRepo;
	@Autowired
	ProductSupplyRepository productSuppliesRepo;

	private final Date contractDateFrom = DateUtils.toDayDate(2015, 04, 01);
	private final Date contractDateTo = DateUtils.toDayDate(2016, 03, 31);

	@Test
	public void test() throws Exception
	{
		//
		// Master data
		final SyncProduct syncProduct1 = dummyDataProducer.createSyncProduct("P1", "P1 packing info");

		//
		// Create a partner with one contract with one line
		final SyncBPartner syncBPartner1 = new SyncBPartner();
		final SyncContract syncContract1 = new SyncContract();
		final SyncContractLine syncContractLine1 = new SyncContractLine();
		{
			syncBPartner1.setUuid(newUUID());
			syncBPartner1.setName("Test");
			syncBPartner1.setSyncContracts(true);

			syncBPartner1.getContracts().add(syncContract1);
			syncContract1.setUuid(newUUID());
			syncContract1.setDateFrom(contractDateFrom);
			syncContract1.setDateTo(contractDateTo);

			syncContract1.getContractLines().add(syncContractLine1);
			syncContractLine1.setUuid(newUUID());
			syncContractLine1.setProduct(syncProduct1);

			agentSync.syncBPartners(SyncBPartnersRequest.of(syncBPartner1));

			Assert.assertEquals(
					"only our contract line shall be present in database"
					// expected
					, Arrays.asList(contractLinesRepo.findByUuid(syncContractLine1.getUuid()))
					// actual
					, contractLinesRepo.findAll());
		}

		//
		// Report a supply on this contract line
		{
			final BPartner bpartner = bpartnerRepo.findByUuid(syncBPartner1.getUuid());
			final Product product = productsRepo.findByUuid(syncProduct1.getUuid());
			final ContractLine contractLine = contractLinesRepo.findByUuid(syncContractLine1.getUuid());
			final Date day = DateUtils.truncToDay(new Date());
			final BigDecimal qty = new BigDecimal("10");
			productSuppliesService.reportSupply(bpartner, product, contractLine, day, qty);
		}

		//
		// Create a new contract line with same product and send the partner with this new line and without the old one
		//
		// case: a contract line for a product was canceled and a new contract line for same product was created
		// expectation: the preview contract line is marked as deleted and the new contract line is created
		{
			final SyncContractLine syncContractLine2 = new SyncContractLine();
			syncContractLine2.setUuid(newUUID());
			syncContractLine2.setProduct(syncProduct1);
			//
			syncContract1.getContractLines().clear();
			syncContract1.getContractLines().add(syncContractLine2);
			//
			agentSync.syncBPartners(SyncBPartnersRequest.of(syncBPartner1));

			Assert.assertEquals(
					"Expect only our second line to be present in database"
					// expected
					, Arrays.asList(contractLinesRepo.findByUuid(syncContractLine2.getUuid()))
					// actual
					, contractLinesRepo.findAll());
		}

		//
		// Delete the contract
		{
			syncBPartner1.getContracts().clear();
			agentSync.syncBPartners(SyncBPartnersRequest.of(syncBPartner1));
			Assert.assertEquals("No contracts", Arrays.asList(), contractLinesRepo.findAll());
			Assert.assertEquals("No contract lines", Arrays.asList(), contractLinesRepo.findAll());
		}

		// //
		// // Dump database:
		// dump("Product Supplies", productSuppliesRepo.findAll());
		// dump("Contract lines", contractLinesRepo.findAll());
	}

	private static final String newUUID()
	{
		return UUID.randomUUID().toString();
	}

	@SuppressWarnings("unused")
	private final void dump(final String msg, final List<?> entries)
	{
		System.out.println(msg + ": ");
		for (final Object entry : entries)
		{
			System.out.println("\n\t" + entry);
		}
	}
}