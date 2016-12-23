/*******************************************************************************
 * Copyright (c) 2015, 2016 Institute for Pervasive Computing, ETH Zurich and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Matthias Kovatsch - creator and main architect
 *    Martin Lanter - architect and re-implementation
 *    Dominique Im Obersteg - parsers and initial implementation
 *    Daniel Pauli - parsers and initial implementation
 *    Kai Hudalla - logging
 *    Bosch Software Innovations GmbH - reduce code duplication, split up into
 *                                      separate test cases, remove wait cycles
 ******************************************************************************/
package org.eclipse.californium.core.test.lockstep;

import static org.eclipse.californium.core.coap.CoAP.Code.GET;
import static org.eclipse.californium.core.coap.CoAP.ResponseCode.CONTENT;
import static org.eclipse.californium.core.coap.CoAP.Type.*;
import static org.eclipse.californium.core.test.lockstep.IntegrationTestTools.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.eclipse.californium.category.Large;
import org.eclipse.californium.core.coap.Request;
import org.eclipse.californium.core.coap.Response;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.network.interceptors.MessageTracer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;


/**
 * This test implements all examples from the blockwise draft 14 for a client.
 */
@Category(Large.class)
public class ObserveClientSideTest {

	private static NetworkConfig CONFIG;

	private LockstepEndpoint server;
	private Endpoint client;
	private int mid = 8000;
	private String respPayload;
	private ClientBlockwiseInterceptor clientInterceptor = new ClientBlockwiseInterceptor();

	@BeforeClass
	public static void init() {
		System.out.println(System.lineSeparator() + "Start " + ObserveClientSideTest.class.getSimpleName());
		CONFIG = new NetworkConfig()
				.setInt(NetworkConfig.Keys.MAX_MESSAGE_SIZE, 16)
				.setInt(NetworkConfig.Keys.PREFERRED_BLOCK_SIZE, 16)
				.setInt(NetworkConfig.Keys.ACK_TIMEOUT, 200) // client retransmits after 200 ms
				.setFloat(NetworkConfig.Keys.ACK_RANDOM_FACTOR, 1f)
				.setFloat(NetworkConfig.Keys.ACK_TIMEOUT_SCALE, 1f);
	}

	@Before
	public void setupEndpoints() throws Exception {

		client = new CoapEndpoint(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), CONFIG);
		client.addInterceptor(clientInterceptor);
		client.addInterceptor(new MessageTracer());
		client.start();
		System.out.println("Client binds to port " + client.getAddress().getPort());
		server = createLockstepEndpoint(client.getAddress());
	}

	@After
	public void shutdownEndpoints() {
		client.destroy();
		server.destroy();
	}

	@AfterClass
	public static void end() {
		System.out.println("End " + ObserveClientSideTest.class.getSimpleName());
	}

	@Test
	public void testGETObserveWithLostACK() throws Exception {
		System.out.println("Observe with lost ACKs:");
		respPayload = generateRandomPayload(10);
		String path = "test";
		int obs = 100;

		Request request = createRequest(GET, path, server);
		SynchronousNotificationListener notificationListener = new SynchronousNotificationListener(request);
		client.addNotificationListener(notificationListener);
		request.setObserve();
		client.sendRequest(request);

		server.expectRequest(CON, GET, path).storeMID("A").storeToken("B").observe(0).go();
		server.sendEmpty(ACK).loadMID("A").go();
		Thread.sleep(50);
		server.sendResponse(CON, CONTENT).loadToken("B").payload(respPayload).mid(++mid).observe(++obs).go();
		server.expectEmpty(ACK, mid).go(); // lost
		clientInterceptor.log(" // lost");
		// retransmit notification using modified payload
		// client must be able to identify this notification as a duplicate and ignore it
		server.sendResponse(CON, CONTENT).loadToken("B").payload(respPayload + "_DUPLICATE").mid(mid).observe(obs).go();
		server.expectEmpty(ACK, mid).go();

		Response response = request.waitForResponse(1000);
		printServerLog(clientInterceptor);

		assertResponseContainsExpectedPayload(response, respPayload);
		System.out.println("Relation established");
		Thread.sleep(1000);

		respPayload = generateRandomPayload(10); // changed
		server.sendResponse(CON, CONTENT).loadToken("B").payload(respPayload).mid(++mid).observe(++obs).go();
		server.expectEmpty(ACK, mid).go(); // lost
		clientInterceptor.log(" // lost");
		server.sendResponse(CON, CONTENT).loadToken("B").payload(respPayload + "_DUPLICATE").mid(mid).observe(obs).go();
		server.expectEmpty(ACK, mid).go();

		Response notification1 = notificationListener.waitForResponse(1000);
		printServerLog(clientInterceptor);

		assertResponseContainsExpectedPayload(notification1, respPayload);
	}

	/**
	 * Verifies behavior of observing a resource that is transferred in multiple chunks using blockwise transfer.
	 * 
	 * THIS TEST CASE FAILS SPORADICALLY BECAUSE OF ITS (INVALID) ASSUMPTION THAT MESSAGES ALWAYS ARRIVE AT
	 * THE SERVER IN THE ORDER THEY HAVE BEEN SENT IN BY THE CLIENT WHEN USING UDP AS THE TRANSPORT PROTOCOL.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testBlockwiseObserve() throws Exception {
		System.out.println("Blockwise Observe:");
		respPayload = generateRandomPayload(40);
		String path = "test";

		Request request = createRequest(GET, path, server);
		request.setObserve();
		SynchronousNotificationListener notificationListener = new SynchronousNotificationListener(request);
		client.addNotificationListener(notificationListener);
		client.sendRequest(request);

		server.expectRequest(CON, GET, path).storeBoth("A").storeToken("T").go();
		server.sendResponse(ACK, CONTENT).loadBoth("A").observe(0).block2(0, true, 16).payload(respPayload.substring(0, 16)).go();
		server.expectRequest(CON, GET, path).storeBoth("B").block2(1, false, 16).go();
		server.sendResponse(ACK, CONTENT).loadBoth("B").block2(1, true, 16).payload(respPayload.substring(16, 32)).go();
		server.expectRequest(CON, GET, path).storeBoth("C").block2(2, false, 16).go();
		server.sendResponse(ACK, CONTENT).loadBoth("C").block2(2, false, 16).payload(respPayload.substring(32, 40)).go();

		Response response = request.waitForResponse(1000);
		printServerLog(clientInterceptor);

		assertResponseContainsExpectedPayload(response, respPayload);

		// normal notification
		server.sendResponse(CON, CONTENT).loadToken("T").mid(++mid).observe(1).block2(0, true, 16).payload(respPayload.substring(0, 16)).go();
		// TODO: allow for messages to be received in arbitrary order
		server.expectEmpty(ACK, mid).go();
		server.expectRequest(CON, GET, path).storeBoth("B").block2(1, false, 16).go();
		server.sendResponse(ACK, CONTENT).loadBoth("B").block2(1, true, 16).payload(respPayload.substring(16, 32)).go();
		server.expectRequest(CON, GET, path).storeBoth("C").block2(2, false, 16).go();
		server.sendResponse(ACK, CONTENT).loadBoth("C").block2(2, false, 16).payload(respPayload.substring(32, 40)).go();

		Response notification = notificationListener.waitForResponse(1000);
		printServerLog(clientInterceptor);

		assertResponseContainsExpectedPayload(notification, respPayload);

		// override transfer with new notification
		server.sendResponse(CON, CONTENT).loadToken("T").mid(++mid).observe(2).block2(0, true, 16).payload(respPayload.substring(0, 16)).go();
		// TODO: allow for messages to be received in arbitrary order
		server.expectEmpty(ACK, mid).go();
		server.expectRequest(CON, GET, path).storeBoth("B").block2(1, false, 16).go();
		server.sendResponse(ACK, CONTENT).loadBoth("B").block2(1, true, 16).payload(respPayload.substring(16, 32)).go();
		server.expectRequest(CON, GET, path).storeBoth("C").block2(2, false, 16).go();
		clientInterceptor.log("\n\n//////// Overriding notification ////////");
		String respPayload3 = "abcdefghijklmnopqrstuvwxyzabcdefghijklmn";
		server.sendResponse(CON, CONTENT).loadToken("T").mid(++mid).observe(3).block2(0, true, 16).payload(respPayload3.substring(0, 16)).go();
		server.expectEmpty(ACK, mid).go();
		// old block
		server.sendResponse(ACK, CONTENT).loadBoth("C").block2(2, false, 16).payload(respPayload.substring(32, 40)).go();
		// new block
		server.expectRequest(CON, GET, path).storeBoth("D").block2(1, false, 16).go();
		server.sendResponse(ACK, CONTENT).loadBoth("D").block2(1, true, 16).payload(respPayload3.substring(16, 32)).go();
		server.expectRequest(CON, GET, path).storeBoth("E").block2(2, false, 16).go();
		server.sendResponse(ACK, CONTENT).loadBoth("E").block2(2, false, 16).payload(respPayload3.substring(32, 40)).go();

		Thread.sleep(50);
		notification = notificationListener.waitForResponse(1000);
		printServerLog(clientInterceptor);

		assertResponseContainsExpectedPayload(notification, respPayload3);

		// override transfer with new notification and conflicting block number
		server.sendResponse(CON, CONTENT).loadToken("T").mid(++mid).observe(4).block2(0, true, 16).payload(respPayload.substring(0, 16)).go();
		// TODO: allow for messages to be received in arbitrary order
		server.expectEmpty(ACK, mid).go();
		server.expectRequest(CON, GET, path).storeBoth("F").block2(1, false, 16).go();
		clientInterceptor.log("\n\n//////// Overriding notification 2 ////////");
		// start new block
		String respPayload4 = "ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMN";
		server.sendResponse(CON, CONTENT).loadToken("T").mid(++mid).observe(5).block2(0, true, 16).payload(respPayload4.substring(0, 16)).go();
		server.expectEmpty(ACK, mid).go();
		server.expectRequest(CON, GET, path).storeBoth("G").block2(1, false, 16).go();

		// old block
		clientInterceptor.log("\n\n//////// Conflicting notification block ////////");
		server.sendResponse(ACK, CONTENT).loadBoth("F").block2(1, true, 16).payload(respPayload.substring(16, 32)).go();
		server.expectRequest(CON, GET, path).storeBoth("H").block2(2, false, 16).go();
		server.sendResponse(ACK, CONTENT).loadBoth("F").block2(2, true, 16).payload(respPayload.substring(32, 40)).go();

		// new block
		server.sendResponse(ACK, CONTENT).loadBoth("G").block2(1, true, 16).payload(respPayload4.substring(16, 32)).go();
		server.expectRequest(CON, GET, path).storeBoth("I").block2(2, false, 16).go();
		server.sendResponse(ACK, CONTENT).loadBoth("I").block2(2, false, 16).payload(respPayload4.substring(32, 40)).go();

		Thread.sleep(50);
		notification = notificationListener.waitForResponse(1000);
		printServerLog(clientInterceptor);

		assertResponseContainsExpectedPayload(notification, respPayload4);

		// cancel
		clientInterceptor.log("\n\n//////// Notification after cancellation ////////");
		server.sendResponse(CON, CONTENT).loadToken("T").mid(++mid).observe(6).block2(0, true, 16).payload(respPayload.substring(0, 16)).go();
		// TODO: allow for messages to be received in arbitrary order
		server.expectEmpty(ACK, mid).go();
		server.expectRequest(CON, GET, path).storeBoth("B").block2(1, false, 16).go();
		// canceling in the middle of blockwise transfer
		client.cancelObservation(request.getToken());
		server.sendResponse(ACK, CONTENT).loadBoth("B").block2(1, true, 16).payload(respPayload.substring(16, 32)).go();

		// notification must not be delivered
		notification = notificationListener.waitForResponse(1000);
		printServerLog(clientInterceptor);

		Assert.assertNull("Client received notification although canceled", notification);

		// next notification must be rejected
		server.sendResponse(CON, CONTENT).loadToken("T").mid(++mid).observe(7).block2(0, true, 16).payload(respPayload.substring(0, 16)).go();
		server.expectEmpty(RST, mid).go();

		// notification must not be delivered
		notification = notificationListener.waitForResponse(1000);
		printServerLog(clientInterceptor);

		Assert.assertNull("Client received notification although canceled", notification);
	}

	/**
	 * Verifies behavior of observing a resource which start a blockwise
	 * transfer and receiving a notification without blockwise at the same time.
	 * 
	 * @throws Exception
	 */
	@Test
	public void testBlockwiseObserveAndNotificationWithoutBlockwise() throws Exception {
		System.out.println("Blockwise Observe:");
		// observer request response will be sent using blockwise
		respPayload = generateRandomPayload(25 * 1024);
		// notification payload sended without blockwise
		String notifpayload = generateRandomPayload(40);
		String path = "test";

		Request request = createRequest(GET, path, server);
		request.setObserve();
		SynchronousNotificationListener notificationListener = new SynchronousNotificationListener(request);
		client.addNotificationListener(notificationListener);

		// Send observe request
		client.sendRequest(request);

		// Expect observe request
		server.expectRequest(CON, GET, path).storeMID("OBS_REQ").storeToken("OBS_TOK").go();
		// Send complete response and blockwise response at the same time \o/ !!
		server.sendEmpty(ACK).loadMID("OBS_REQ").go();
		int mid_block = 111;
		server.sendResponse(CON, CONTENT).loadToken("OBS_TOK").observe(1).mid(mid_block).block2(0, true, 1024)
				.payload(respPayload.substring(0, 1024)).go();
		int mid_notif = 222;
		server.sendResponse(CON, CONTENT).loadToken("OBS_TOK").observe(2).mid(mid_notif)
				.payload(notifpayload)
				.go();

		// Expect for block request and ACKs
		server.expectEmpty(ACK, mid_block).go();
		server.expectRequest(CON, GET, path).storeMID("BLOCK_TOK").storeToken("SECOND_BLOCK").block2(1, false, 1024)
				.go();
		server.expectEmpty(ACK, mid_notif).go();

		// Send next block
		server.sendResponse(ACK, CONTENT).loadMID("BLOCK_TOK").loadToken("SECOND_BLOCK").block2(1, true, 1024)
				.payload(respPayload.substring(1024, 2048)).go();

		// Check that we get the most recent response (with the higher observe
		// option value)
		Response response = request.waitForResponse();
		assertResponseContainsExpectedPayload(response, notifpayload);


		// Send new notif without block
		notifpayload = generateRandomPayload(40);
		server.sendResponse(CON, CONTENT).loadToken("OBS_TOK").observe(3).mid(++mid_notif)
				.payload(notifpayload).go();
		response = notificationListener.waitForResponse(1000);
		assertResponseContainsExpectedPayload(response, notifpayload);

		// Send new notif without block
		server.sendResponse(CON, CONTENT).loadToken("OBS_TOK").observe(4).mid(++mid_notif).payload(notifpayload).go();
		response = notificationListener.waitForResponse(1000);
		assertResponseContainsExpectedPayload(response, notifpayload);

		printServerLog(clientInterceptor);
	}
}
