/**
 * Copyright 2012 Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 * Authors: Patrick Schäfer
 */

package org.xtreemfs.contrib.provisioning;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.BeforeClass;
import org.junit.Test;
import org.xtreemfs.common.ReplicaUpdatePolicies;
import org.xtreemfs.contrib.provisioning.JsonRPC.METHOD;
import org.xtreemfs.contrib.provisioning.LibJSON.AccessTypes;
import org.xtreemfs.contrib.provisioning.LibJSON.Addresses;
import org.xtreemfs.contrib.provisioning.LibJSON.Allocation;
import org.xtreemfs.contrib.provisioning.LibJSON.Attributes;
import org.xtreemfs.contrib.provisioning.LibJSON.MetricReq;
import org.xtreemfs.contrib.provisioning.LibJSON.MetricResp;
import org.xtreemfs.contrib.provisioning.LibJSON.ReservationStati;
import org.xtreemfs.contrib.provisioning.LibJSON.ReservationStatus;
import org.xtreemfs.contrib.provisioning.LibJSON.ReservationID;
import org.xtreemfs.contrib.provisioning.LibJSON.Resource;
import org.xtreemfs.contrib.provisioning.LibJSON.ResourceCapacity;
import org.xtreemfs.contrib.provisioning.LibJSON.ResourceMapper;
import org.xtreemfs.contrib.provisioning.LibJSON.Resources;
import org.xtreemfs.foundation.json.JSONException;
import org.xtreemfs.foundation.json.JSONString;

import com.thetransactioncompany.jsonrpc2.JSONRPC2ParseException;
import com.thetransactioncompany.jsonrpc2.JSONRPC2Response;


public class JSONRPCTest extends AbstractTestCase {

  @BeforeClass public static void setUpTest() throws Exception {
    STARTUP_LOCAL = true;
  }

  /**
   * Test protocol errors.
   * @throws JSONRPC2ParseException
   */
  @Test
  public void parameterErrors() throws JSONRPC2ParseException {
    System.out.println("parameterErrors");

    // test unknown method
    JSONRPC2Response res = callJSONRPC("listVolumessss", "1");
    checkSuccess(res, true);

    // empty method-name
    res = callJSONRPC("");
    checkSuccess(res, true);

    // missing parameters
    res = callJSONRPC(METHOD.createReservation);
    checkSuccess(res, true);

    res = callJSONRPC(METHOD.releaseReservation);
    checkSuccess(res, true);

    String policy = ReplicaUpdatePolicies.REPL_UPDATE_PC_WARONE;
    res = callJSONRPC(METHOD.createReservation, "testVolume_policies", owner, ownerGroup, mode, policy, "2", "2");
    checkSuccess(res, true);
  }

  /**
   * Test resource aggregation.
   * @throws JSONRPC2ParseException
   * @throws JSONException
   */
  @Test
  public void calculateResourceCapacity() throws JSONRPC2ParseException, JSONException {
    System.out.println("calculateResourceCapacity");

    double originalThroughput = 10.0;
    double reserveThroughput = 8.0;
    double releaseThroughput = 9.0;

    double originalCapacity = 100.0;
    double reserveCapacity = 80.0;
    double releaseCapacity = 90.0;

    ResourceCapacity rc = new ResourceCapacity(
        new Resource(
            "/"+dirAddress+"/storage/random",
            "Storage",
            null,
            new Attributes(
                originalCapacity,
                originalThroughput,
                AccessTypes.RANDOM)
            ),
            new LibJSON.AllocationResource(
                new Attributes(
                    reserveCapacity,
                    reserveThroughput,
                    AccessTypes.RANDOM)
                ),
                new LibJSON.ReleaseResource(
                    new Attributes(
                        releaseCapacity,
                        releaseThroughput,
                        AccessTypes.RANDOM)
                    )
        );

    JSONRPC2Response res = callJSONRPC(METHOD.calculateCapacity, rc);
    ResourceMapper resources = parseResult(res, ResourceMapper.class);
    double capacity = resources.getResource().getAttributes().getCapacity();
    double throughput = resources.getResource().getAttributes().getThroughput();

    assertTrue(capacity == (originalCapacity + releaseCapacity - reserveCapacity));
    assertTrue(throughput == (originalThroughput + releaseThroughput - reserveThroughput));

    checkSuccess(res, false);
  }

  /**
   * Test resource aggregation.
   * @throws JSONRPC2ParseException
   * @throws JSONException
   */
  @Test
  public void calculateResourceCapacityWithInvalidInput() throws JSONRPC2ParseException, JSONException {
    System.out.println("calculateResourceCapacity");

    double originalThroughput = 10.0;
    double reserveThroughput = 8.0;
    double releaseThroughput = 9.0;

    double originalCapacity = 100.0;
    double reserveCapacity = 80.0;
    double releaseCapacity = 90.0;

    ResourceCapacity rc = new ResourceCapacity(
        new Resource(
            "/"+dirAddress+"/storage/random",
            "Storage",
            null,
            new Attributes(
                originalCapacity,
                originalThroughput,
                AccessTypes.RANDOM)
            ),
            new LibJSON.AllocationResource(
                new Attributes(
                    reserveCapacity,
                    reserveThroughput,
                    AccessTypes.SEQUENTIAL)
                ),
                new LibJSON.ReleaseResource(
                    new Attributes(
                        releaseCapacity,
                        releaseThroughput,
                        AccessTypes.RANDOM)
                    )
        );

    JSONRPC2Response res = callJSONRPC(METHOD.calculateCapacity, rc);
    ResourceMapper resources = parseResult(res, ResourceMapper.class);

    assertTrue(resources.getResource() == null);

    checkSuccess(res, false);
  }

  /**
   * Test resource aggregation.
   * @throws JSONRPC2ParseException
   * @throws JSONException
   */
  @Test
  public void calculateResourceCapacityWithoutRemainingResources() throws JSONRPC2ParseException, JSONException {
    System.out.println("calculateResourceCapacity");

    double originalThroughput = 10.0;
    double originalCapacity = 100.0;

    ResourceCapacity rc = new ResourceCapacity(
        new Resource(
            "/"+dirAddress+"/storage/random",
            "Storage",
            null,
            new Attributes(
                originalCapacity,
                originalThroughput,
                AccessTypes.RANDOM)
            ),
            new LibJSON.AllocationResource(
                new Attributes(
                    originalCapacity,
                    originalThroughput,
                    AccessTypes.RANDOM)
                ),
                new LibJSON.ReleaseResource(
                    new Attributes(
                        0.0,
                        0.0,
                        AccessTypes.RANDOM)
                    )
        );

    JSONRPC2Response res = callJSONRPC(METHOD.calculateCapacity, rc);
    ResourceMapper resources = parseResult(res, ResourceMapper.class);
    double capacity = resources.getResource().getAttributes().getCapacity();
    double throughput = resources.getResource().getAttributes().getThroughput();

    assertTrue(capacity == 0.0);
    assertTrue(throughput == 0.0);

    checkSuccess(res, false);
  }

  /**
   * Test getting all resource types.
   * @throws JSONRPC2ParseException
   * @throws JSONException
   */
  @Test
  public void getResourceTypes() throws JSONRPC2ParseException, JSONException {
    System.out.println("getResourceTypes");

    JSONRPC2Response res = callJSONRPC(METHOD.getAllocSpec);
    checkSuccess(res, false);
  }


  /**
   * Test creating volumes.
   * @throws JSONRPC2ParseException
   * @throws JSONException
   */
  @Test
  public void createAndDeleteVolumes() throws JSONRPC2ParseException, JSONException {
    System.out.println("createAndDeleteVolumes");

    // create a volume
    Allocation resource = new Allocation(
        new Resource(
            "/"+dirAddress+"/storage/random",
            "Storage",
            null,
            new Attributes(
                100.0,
                10.0,
                AccessTypes.RANDOM)),
                new LibJSON.MonitorCreate(
                    "CAPACITY_UTILIZATION",
                    new LibJSON.Type(10, "Sum"),
                    1000
                    ));

    JSONRPC2Response res = callJSONRPC(METHOD.createReservation, gson.toJson(resource));
    checkSuccess(res, false);
    ReservationID resources = parseResult(res, ReservationID.class);
    System.out.println("IResID: " + resources.getReservationID().iterator().next());

    // create a second volume
    res = callJSONRPC(METHOD.createReservation, resource);
    checkSuccess(res, false);
    ReservationID resources2 = parseResult(res, ReservationID.class);
    System.out.println("IResID: " + resources2.getReservationID().iterator().next());

    // delete the second volume
    ReservationID reservations = new ReservationID(
        resources2.getReservationID().iterator().next()
        );
    res = callJSONRPC(METHOD.releaseReservation, reservations);
    checkSuccess(res, false);

    // check if there is only one volume left
    res = callJSONRPC(METHOD.listReservations);
    checkSuccess(res, false);
    Addresses volumes = parseResult(res, Addresses.class);

    assertTrue(volumes.Addresses.size() == 1);

    String volume1 = LibJSON.stripVolumeName(resources.getReservationID().iterator().next());
    String volume2 = LibJSON.stripVolumeName(resources2.getReservationID().iterator().next());

    String response = res.toString();
    assertTrue(response.contains(volume1));
    assertFalse(response.contains(volume2));
  }


  /**
   * Creates 10 volumes and cleans up all volumes.
   * Checks if all volumes have been deleted successfully
   * @throws JSONRPC2ParseException
   * @throws JSONException
   */
  @Test
  public void createListAndDeleteVolumes() throws JSONRPC2ParseException, JSONException {
    System.out.println("createListAndDeleteVolumes");

    // create volumes

    for (int i = 0; i < 5; i++) {
      // create a volume
      Allocation resource = new Allocation(
          new Resource(
              "/"+dirAddress+"/storage/random",
              "Storage",
              null,
              new Attributes(
                  100.0,
                  10.0,
                  AccessTypes.RANDOM)),
                  new LibJSON.MonitorCreate(
                      "CAPACITY_UTILIZATION",
                      new LibJSON.Type(10, "Sum"),
                      1000
                      ));

      // create a volume
      JSONRPC2Response res = callJSONRPC(METHOD.createReservation, resource);
      checkSuccess(res, false);
      ReservationID volumeName = parseResult(res, ReservationID.class);

      // check if the volume was created
      ReservationID reservations = new ReservationID(
          volumeName.getReservationID().iterator().next());
      JSONRPC2Response res2 = callJSONRPC(METHOD.checkReservation, reservations);
      checkSuccess(res2, false);
      ReservationStati result2 = parseResult(res2, ReservationStati.class);

      boolean found = false;
      for (ReservationStatus status : result2.getInstances().values()) {
        if (status.Address.contains(volumeName.getReservationID().iterator().next())) {
          found = true;
          break;
        }
      }
      assertTrue(found);
    }

    // list all volumes
    JSONRPC2Response res = callJSONRPC(METHOD.listReservations);
    checkSuccess(res, false);
    Addresses volumes = parseResult(res, Addresses.class);

    for (String volume : volumes.getAddresses()) {
      System.out.println("deleting Volume " + volume);

      // remove each volume
      ReservationID addresses = new ReservationID(volume);
      res = callJSONRPC(
          METHOD.releaseReservation,
          new JSONString(gson.toJson(addresses)));
      checkSuccess(res, false);
    }

    System.out.println("List volumes ");
    res = callJSONRPC(METHOD.listReservations);
    checkSuccess(res, false);

    Addresses result2 = parseResult(res, Addresses.class);
    assertTrue(result2.Addresses.isEmpty());
  }


  /**
   * List the available resources
   * @throws JSONRPC2ParseException
   * @throws JSONException
   */
  @Test
  public void getAvailableResources() throws JSONRPC2ParseException, JSONException {
    System.out.println("getAvailableResources");

    JSONRPC2Response res = callJSONRPC(METHOD.getResources);
    checkSuccess(res, false);
  }


  /**
   * Creates a volume and lists the available resources
   * @throws JSONRPC2ParseException
   * @throws JSONException
   */
  @Test
  public void calculateResourceAgg() throws JSONRPC2ParseException, JSONException {
    System.out.println("calculateResourceAgg");

    // create a volume
    Resources resource = new Resources(
        new Resource(
            "/"+dirAddress+"/storage/random",
            "Storage",
            null,
            new Attributes(
                100.0,
                100.0,
                AccessTypes.SEQUENTIAL)));

    JSONRPC2Response res = callJSONRPC(METHOD.calculateResourceAgg, resource);
    checkSuccess(res, false);
  }


  /**
   * Creates a volume and lists the available resources
   * @throws JSONRPC2ParseException
   * @throws JSONException
   */
  @Test
  public void createListAndCheckReservation() throws JSONRPC2ParseException, JSONException {
    System.out.println("createListAndCheckReservation");

    // create a volume
    Allocation resource = new Allocation(
        new Resource(
            "/"+dirAddress+"/storage/random",
            "Storage",
            null,
            new Attributes(
                100.0,
                100.0,
                AccessTypes.SEQUENTIAL)),
                new LibJSON.MonitorCreate(
                    "CAPACITY_UTILIZATION",
                    new LibJSON.Type(10, "AVG"),
                    1000
                    ));

    // parametersMap.put("password", "");
    JSONRPC2Response res = callJSONRPC(METHOD.createReservation, resource);
    checkSuccess(res, false);

    JSONRPC2Response res2 = callJSONRPC(METHOD.listReservations);
    checkSuccess(res2, false);

    res = callJSONRPC(METHOD.getResources);
    checkSuccess(res, false);
  }

  
  /**
   * Get the metrics
   * @throws JSONRPC2ParseException
   * @throws JSONException
   */
  @Test
  public void getMetrics() throws JSONRPC2ParseException, JSONException {
    System.out.println("getMetric");

    // create a volume
    Allocation resource1 = new Allocation(
        new Resource(
            "/"+dirAddress+"/storage/random",
            "Storage",
            null,
            new Attributes(
                100.0,
                10.0,
                AccessTypes.RANDOM)),
                new LibJSON.MonitorCreate(
                    "CAPACITY_UTILIZATION",
                    new LibJSON.Type(1, ""),
                    1000
                    ));

     Allocation resource2 = new Allocation(
        new Resource(
            "/"+dirAddress+"/storage/random",
            "Storage",
            null,
            new Attributes(
                100.0,
                10.0,
                AccessTypes.RANDOM)),
                new LibJSON.MonitorCreate(
                    "CAPACITY_UTILIZATION",
                    new LibJSON.Type(1, ""),
                    1000
                    ));

    JSONRPC2Response res1 = callJSONRPC(METHOD.createReservation, gson.toJson(resource1));
    checkSuccess(res1, false);
    ReservationID resources1 = parseResult(res1, ReservationID.class);
    String volume1 = LibJSON.stripVolumeName(resources1.getReservationID().iterator().next());

    JSONRPC2Response res2 = callJSONRPC(METHOD.createReservation, gson.toJson(resource2));
    checkSuccess(res2, false);
    ReservationID resources2 = parseResult(res2, ReservationID.class);
    String volume2 = LibJSON.stripVolumeName(resources2.getReservationID().iterator().next());

    try {
      Thread.sleep(3000);
    } catch(InterruptedException ex) {}

    String response1 = res1.toString();
    assertTrue(response1.contains(volume1));

    MetricReq metricResource1 = new MetricReq("", resources1.getReservationID().iterator().next(), 1);

    JSONRPC2Response metricRes1 = callJSONRPC(METHOD.getMetrics, metricResource1);
    checkSuccess(metricRes1, false);
    
    MetricResp result1 = parseResult(metricRes1, MetricResp.class);

    metricResource1 = new MetricReq("", resources1.getReservationID().iterator().next(), 2);
    metricRes1 = callJSONRPC(METHOD.getMetrics, metricResource1);
    checkSuccess(metricRes1, false);


    String response2 = res2.toString();
    assertTrue(response2.contains(volume2));

    MetricReq metricResource2 = new MetricReq("", resources2.getReservationID().iterator().next(), 1);

    JSONRPC2Response metricRes2 = callJSONRPC(METHOD.getMetrics, metricResource2);
    checkSuccess(metricRes2, false);

    MetricResp result2 = parseResult(metricRes2, MetricResp.class);

    metricResource2 = new MetricReq("", resources2.getReservationID().iterator().next(), -1);
    metricRes2 = callJSONRPC(METHOD.getMetrics, metricResource2);
    checkSuccess(metricRes2, false);
  }

}