package org.xtreemfs.contrib.provisioning;

import java.io.IOException;
import java.util.*;

import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.xtreemfs.common.libxtreemfs.exceptions.AddressToUUIDNotFoundException;
import org.xtreemfs.common.libxtreemfs.exceptions.VolumeNotFoundException;
import org.xtreemfs.contrib.provisioning.LibJSON.Addresses;
import org.xtreemfs.contrib.provisioning.LibJSON.Allocation;
import org.xtreemfs.contrib.provisioning.LibJSON.MetricReq;
import org.xtreemfs.contrib.provisioning.LibJSON.MetricResp;
import org.xtreemfs.contrib.provisioning.LibJSON.ReservationStati;
import org.xtreemfs.contrib.provisioning.LibJSON.ReservationID;
import org.xtreemfs.contrib.provisioning.LibJSON.Resource;
import org.xtreemfs.contrib.provisioning.LibJSON.ResourceCapacity;
import org.xtreemfs.contrib.provisioning.LibJSON.Resources;
import org.xtreemfs.contrib.provisioning.LibJSON.Response;
import org.xtreemfs.contrib.provisioning.LibJSON.Types;

@Consumes("application/json")
@Produces("application/json")
public class Rest extends JsonRPC {

    @POST
    @Path("/createReservation")
    public Response<ReservationID> createReservation(Allocation res) {
        try {
            return new Response<ReservationID>(LibJSON.createReservation(
                    res,
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    dirAddresses,
                    sslOptions,
                    AbstractRequestHandler.getGroups(),
                    AbstractRequestHandler.getAuth(this.adminPassword),
                    client));

        } catch (Exception e) {
            return new Response<ReservationID>(
                    null, new LibJSON.Error(e.getLocalizedMessage(), -1)
            );
        }
    }

    @DELETE
    @Path("/releaseReservation")
    public Response<Map<Object, Object>> releaseReservation(ReservationID res) {
        try {
            LibJSON.releaseReservation(
                    res,
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    AbstractRequestHandler.getGroups(),
                    AbstractRequestHandler.getAuth(this.adminPassword),
                    client
            );
        } catch (Exception e) {
            return new Response<Map<Object, Object>>(
                    null, new LibJSON.Error(e.getLocalizedMessage(), -1)
            );
        }
        return new Response<Map<Object, Object>>(new HashMap<Object, Object>());
    }
    
    @POST
    @Path("/calculateResourceAgg")
    @Deprecated
    public Response<Resource> calculateResourceAgg(Resources res) {
        try {
            return new Response<Resource>(LibJSON.calculateResourceAgg(
                    res,
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    AbstractRequestHandler.getGroups(),
                    AbstractRequestHandler.getAuth(this.adminPassword),
                    client));
        } catch (Exception e) {
            return new Response<Resource>(
                    null, new LibJSON.Error(e.getLocalizedMessage(), -1)
            );
        }
    }

    @POST
    @Path("/calculateCapacity")
    public Response<Map<String, Resource>> calculateCapacity(ResourceCapacity res) {
        try {
            return new Response<Map<String, Resource>>(LibJSON.calculateCapacity(
                    res,
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    AbstractRequestHandler.getGroups(),
                    AbstractRequestHandler.getAuth(this.adminPassword),
                    client));
        } catch (Exception e) {
            return new Response<Map<String, Resource>>(
                    null, new LibJSON.Error(e.getLocalizedMessage(), -1)
            );
        }
    }

    @GET
    @Path("/getAllocSpec")
    public Response<Types> getAllocSpec() {
        try {
            return new Response<Types>(LibJSON.getAllocSpec());
        } catch (Exception e) {
            return new Response<Types>(
                    null, new LibJSON.Error(e.getLocalizedMessage(), -1)
            );
        }
    }

    @POST
    @Path("/checkReservation")
    public Response<ReservationStati> checkReservation(ReservationID res)
            throws AddressToUUIDNotFoundException, VolumeNotFoundException, IOException {
        try {
            return new Response<ReservationStati>(LibJSON.checkReservation(
                    res,
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    dirAddresses,
                    sslOptions,
                    AbstractRequestHandler.getGroups(),
                    AbstractRequestHandler.getAuth(this.adminPassword),
                    this.client
            ));
        } catch (Exception e) {
            return new Response<ReservationStati>(
                    null, new LibJSON.Error(e.getLocalizedMessage(), -1)
            );
        }
    }

    @GET
    @Path("/getResources")
    public Response<Resources> getResources() {
        try {
            return new Response<Resources>(
                    LibJSON.getResources(
                            LibJSON.generateSchedulerAddress(schedulerAddress),
                            dirAddresses,
                            AbstractRequestHandler.getGroups(),
                            AbstractRequestHandler.getAuth(adminPassword),
                            client)
            );
        } catch (Exception e) {
            return new Response<Resources>(
                    null, new LibJSON.Error(e.getLocalizedMessage(), -1)
            );
        }
    }

    @POST
    @Path("/getMetrics")
    public Response<MetricResp> getMetrics(MetricReq res) {
        try {
            return new Response<MetricResp>(
                    LibJSON.getMetrics(
                            res,
                            LibJSON.generateSchedulerAddress(schedulerAddress),
                            AbstractRequestHandler.getGroups(),
                            AbstractRequestHandler.getAuth(adminPassword),
                            client)
            );
        } catch (Exception e) {
            return new Response<MetricResp>(
                    null, new LibJSON.Error(e.getLocalizedMessage(), -1)
            );
        }
    }
    
    @GET
    @Path("/listReservations")
    public Response<Addresses> listReservations() {
        try {
            return new Response<Addresses>(
                    LibJSON.listReservations(dirAddresses, client)
            );
        } catch (Exception e) {
            return new Response<Addresses>(
                    null, new LibJSON.Error(e.getLocalizedMessage(), -1)
            );
        }
    }

    @DELETE
    @Path("/releaseAllReservations")
    public Response<Map<Object, Object>> releaseAllReservations() {
        try {
            LibJSON.releaseAllReservations(
                    LibJSON.generateSchedulerAddress(schedulerAddress),
                    dirAddresses,
                    AbstractRequestHandler.getGroups(),
                    AbstractRequestHandler.getAuth(adminPassword),
                    client);
        } catch(IOException e) {
            return new Response<Map<Object, Object>>(
                    null, new LibJSON.Error(e.getLocalizedMessage(), -1)
            );
        }
        return new Response<Map<Object, Object>>(new HashMap<Object, Object>());
    }
}
