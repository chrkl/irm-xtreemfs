package org.xtreemfs.contrib.provisioning;

import org.codehaus.jackson.annotate.JsonAutoDetect;
import org.codehaus.jackson.annotate.JsonAutoDetect.Visibility;
import org.codehaus.jackson.annotate.JsonIgnoreProperties;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.xtreemfs.common.libxtreemfs.Client;
import org.xtreemfs.common.libxtreemfs.Options;
import org.xtreemfs.common.libxtreemfs.Volume;
import org.xtreemfs.common.libxtreemfs.exceptions.AddressToUUIDNotFoundException;
import org.xtreemfs.common.libxtreemfs.exceptions.PosixErrorException;
import org.xtreemfs.common.libxtreemfs.exceptions.VolumeNotFoundException;
import org.xtreemfs.foundation.SSLOptions;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.Auth;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.UserCredentials;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.AccessControlPolicyType;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.KeyValuePair;
import org.xtreemfs.pbrpc.generatedinterfaces.MRC;
import org.xtreemfs.pbrpc.generatedinterfaces.Scheduler;
import org.xtreemfs.pbrpc.generatedinterfaces.Scheduler.freeResourcesResponse;

import javax.xml.bind.annotation.XmlRootElement;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.*;


/**
 *
 * @author bzcschae
 *
 */
public class LibJSON {

  private static IRMConfig irmConfig = null;
  private static CapacityMonitor capacityMonitor = null;

  public static void setIrmConfig(IRMConfig config) {
    LibJSON.irmConfig = config;
  }

  public static void setCapacityMonitor(CapacityMonitor capacityMonitor) {
    LibJSON.capacityMonitor = capacityMonitor;
  }

  public static Volume openVolume(
      String volume_name,
      SSLOptions sslOptions,
      Client client)
          throws AddressToUUIDNotFoundException, VolumeNotFoundException,
          IOException {
    Options options = new Options();
    return client.openVolume(volume_name, sslOptions, options);
  }

  public static String stripVolumeName(String volume_name) {
    if (volume_name.contains("/")) {
      String[] parts = volume_name.split("/");
      volume_name = parts[parts.length-1];
    }
    return volume_name;
  }


  public static String generateSchedulerAddress(InetSocketAddress schedulerAddress) {
    InetSocketAddress address = schedulerAddress;
    return address.getHostName() + ":" + address.getPort();
  }


  public static String[] generateDirAddresses(InetSocketAddress[] dirAddresses) {
    String[] dirAddressesString = new String[dirAddresses.length];
    for (int i = 0; i < dirAddresses.length; i++) {
      InetSocketAddress address = dirAddresses[i];
      dirAddressesString[i] = address.getHostName() + ":" + address.getPort();
    }
    return dirAddressesString;
  }


  public static String createNormedVolumeName(String volume_name, InetSocketAddress[] dirAddresses) {
    String[] dirAddressesString = generateDirAddresses(dirAddresses);
    StringBuffer normed_volume_names = new StringBuffer();
    for (String s : dirAddressesString) {
      normed_volume_names.append(s);
      normed_volume_names.append(",");
    }
    normed_volume_names.deleteCharAt(normed_volume_names.length() - 1);
    normed_volume_names.append("/" + volume_name);

    return normed_volume_names.toString();
  }


  public static MetricResp getMetrics(
      MetricReq res,
      String schedulerAddress,
      UserCredentials uc,
      Auth auth,
      Client client
      ) {
    MetricResp response = new MetricResp();
    String volume = stripVolumeName(res.getAddress());
    if(volume == null || volume.equals("")) {
      volume = stripVolumeName(res.getReservationID());
    }
    String capacityUtilization = capacityMonitor.getCapacityUtilization(volume, res.getEntry());
    HashMap<String, String> metrics = new HashMap<String, String>();
    metrics.put("CAPACITY_UTILIZATION", capacityUtilization);
    //TODO: add THROUGHPUT_UTILIZATION
    response.setMetrics(metrics);
    return response;
  }
  
  
  public static ReservationID createReservation(
      Allocation res,
      String schedulerAddress,
      InetSocketAddress[] dirAddresses,
      SSLOptions sslOptions,
      UserCredentials uc,
      Auth auth,
      Client client) throws Exception {
    ReservationID reservations = new ReservationID();

    try {
      // search for storage resource
      for (Resource resource : res.Allocation) {
        if (resource.Type.toLowerCase().equals("storage")) {
          Integer capacity = (int)resource.Attributes.Capacity;
          Integer throughput = (int)resource.Attributes.Throughput;
          AccessTypes accessType = resource.Attributes.AccessType != null?resource.Attributes.AccessType:AccessTypes.SEQUENTIAL;
          boolean randomAccess = accessType == AccessTypes.RANDOM;

          int octalMode = Integer.parseInt("777", 8);

          String volume_name = "volume-"+UUID.randomUUID().toString();

          // Create the volumes
          client.createVolume(
              schedulerAddress,
              auth,
              uc,
              volume_name,
              octalMode,
              "user",
              "user",
              AccessControlPolicyType.ACCESS_CONTROL_POLICY_POSIX,
              128*1024,
              new ArrayList<KeyValuePair>(),  // volume attributes
              capacity,
              randomAccess? throughput : 0,
                  !randomAccess? throughput : 0,
                      false);

          // create a string similar to:
          // [<protocol>://]<DIR-server-address>[:<DIR-server-port>]/<Volume Name>
          reservations.addAll(new ReservationID(
                  //createNormedVolumeName(volume_name, dirAddresses)
                  volume_name
          ));

          if(res.Monitor != null && res.Monitor.Storage != null) {
              if (res.getMonitor().Storage.containsKey("CAPACITY_UTILIZATION")) {
                  int pollInterval = (res.getMonitor().PollTime / 1000) *
                          res.getMonitor().getStorage().get("CAPACITY_UTILIZATION").getPollTimeMultiplier();
                  LibJSON.capacityMonitor.addVolume(client, sslOptions, uc, auth, volume_name, pollInterval,
                          (long) resource.getAttributes().getCapacity() * 1024 * 1024 * 1024);
              }
          }
        }
      }
      return reservations;

    } catch (Exception e) {
      try {
        // free all resources, if we could not reserve all required resources.
        releaseReservation(
            reservations,
            schedulerAddress,
            uc,
            auth,
            client
            );
      } catch (Exception ee) {
        ee.printStackTrace();
      }
      throw e;
    }
  }


  public static void releaseReservation(
      ReservationID res,
      String schedulerAddress,
      UserCredentials uc,
      Auth auth,
      Client client) throws IOException,
      PosixErrorException, AddressToUUIDNotFoundException {
    if (res != null && res.getReservationID() != null) {
      for (String volume : res.getReservationID()) {

        String volume_name = stripVolumeName(volume);
        LibJSON.capacityMonitor.removeVolume(volume_name);

        // first delete the volume
        client.deleteVolume(
                auth,
                uc,
                volume_name);

        // now delete the reservation of resources
        client.deleteReservation(
                schedulerAddress,
                auth,
                uc,
                volume_name);
      }
    }
  }


  public static ReservationStati checkReservation(
      ReservationID res,
      String schedulerAddress,
      InetSocketAddress[] dirAddresses,
      SSLOptions sslOptions,
      UserCredentials uc,
      Auth auth,
      Client client
      ) throws AddressToUUIDNotFoundException, VolumeNotFoundException, IOException {

    ReservationStati stati = new ReservationStati();

    for (String volume_name : res.getReservationID()) {

      volume_name = stripVolumeName(volume_name);

      LibJSON.openVolume(volume_name, sslOptions, client);

      // obtain the size of the reservation
      Scheduler.reservation reservation = client.getReservation(
          schedulerAddress,
          auth,
          uc,
          volume_name);

      //      boolean sequential = reservation.getType() == Scheduler.reservationType.STREAMING_RESERVATION;
      boolean random = reservation.getType() == Scheduler.reservationType.RANDOM_IO_RESERVATION;

      // return a string like
      // [<protocol>://]<DIR-server-address>[:<DIR-server-port>]/<Volume Name>
      ReservationStatus reservStatus = new ReservationStatus(
          true,
          createNormedVolumeName(volume_name, dirAddresses),
          new Resource(
              "/XtreemFS/"
                  + dirAddresses[0].getAddress().getHostAddress()
                  + "/storage/" + (random? "random":"sequential"),
                  "Storage",
                  null,
                  new Attributes(
                      reservation.getCapacity(),
                      random? reservation.getRandomThroughput() : reservation.getStreamingThroughput(),
                          random? AccessTypes.RANDOM : AccessTypes.SEQUENTIAL
                      )
              )
          );

      stati.addReservationStatus(volume_name, reservStatus);
    }

    return stati;
  }


  public static Addresses listReservations(
      InetSocketAddress[] dirAddresses,
      Client client) throws IOException {
    // list volumes
    ArrayList<String> volumeNames = new ArrayList<String>();

    String[] volumes = client.listVolumeNames();
    for (String volume_name : volumes) {
      volumeNames.add(createNormedVolumeName(volume_name, dirAddresses));
    }

    Addresses addresses = new Addresses(
        volumeNames
        );
    return addresses;
  }


  @Deprecated
  public static Resource calculateResourceAgg(
      Resources resources,
      String schedulerAddress,
      UserCredentials uc,
      Auth auth,
      Client client) throws IOException {

    // obtain the free resources as upper limit
    freeResourcesResponse freeResources
    = client.getFreeResources(
        schedulerAddress,
        auth,
        uc);

    double newThroughput = 0.0;
    double newCapacity = 0.0;

    Resource firstResource = resources.getResources().values().iterator().next();
    AccessTypes type = firstResource.getAttributes().getAccessType();

    // calculate the aggregation
    for (Resource resource : resources.getResources().values()) {
      try {
        newCapacity += resource.getAttributes().getCapacity();
      } catch (Exception e) {
        // silent
      }
      try {
        newThroughput = Math.max(resource.getAttributes().getThroughput(), newThroughput);
      } catch (Exception e) {
        // silent
      }
    }

    // limit by available resources
    newCapacity = Math.min(
        type == AccessTypes.RANDOM ? freeResources.getRandomCapacity() : freeResources.getStreamingCapacity(),
            newCapacity);
    newThroughput = Math.min(
        type == AccessTypes.RANDOM ? freeResources.getRandomThroughput() : freeResources.getStreamingThroughput(),
            newThroughput);

    return new Resource(
        firstResource.getID(),
        "Storage",
        null,
        new Attributes(
            newCapacity,
            newThroughput,
            type
            )
        );
  }


  public static Map<String, Resource> calculateCapacity(
          ResourceCapacity resourceCapacity,
          String schedulerAddress,
          UserCredentials uc,
          Auth auth,
          Client client) throws IOException {

    freeResourcesResponse freeResources
    = client.getFreeResources(
        schedulerAddress,
        auth,
        uc);

    List<AllocationResource> allocation = resourceCapacity.getAllocation();
    List<ReleaseResource> release = resourceCapacity.getRelease();

    AccessTypes type = resourceCapacity.getResource().getAttributes().getAccessType();
    double remainingCapacity = resourceCapacity.getResource().getAttributes().getCapacity();
    double remainingThrough = resourceCapacity.getResource().getAttributes().getThroughput();

    HashMap<String, Resource> result = new HashMap<String, Resource>();

    if (allocation != null) {
      for (AllocationResource res : allocation) {
        Attributes attr = res.getAttributes();
        if(attr.getAccessType() == null || attr.getAccessType() != type) {
          // return empty result (=> invalid request), if access types does not match
          return result;
        }
        try {
          remainingCapacity -= attr.getCapacity();
        } catch (Exception e) {
          // silent
        }
        try {
          remainingThrough -= attr.getThroughput();
        } catch (Exception e) {
          // silent
        }
      }
    }

    if (release != null) {
      for (ReleaseResource rel : release) {
        Attributes attr = rel.getAttributes();
        if(attr.getAccessType() == null || attr.getAccessType() != type) {
          // return empty result (=> invalid request), if access types does not match
          return result;
        }
        try {
          remainingCapacity += attr.getCapacity();
        } catch (Exception e) {
          // silent
        }
        try {
          remainingThrough += attr.getThroughput();
        } catch (Exception e) {
          // silent
        }
      }
    }

    remainingCapacity = Math.min(
        type == AccessTypes.RANDOM ? freeResources.getRandomCapacity() : freeResources.getStreamingCapacity(),
            remainingCapacity);
    remainingThrough = Math.min(
        type == AccessTypes.RANDOM ? freeResources.getRandomThroughput() : freeResources.getStreamingThroughput(),
            remainingThrough);

    if(remainingCapacity >= 0 && remainingThrough >= 0) {

      result.put("Resource",
          new Resource(
              resourceCapacity.getResource().getID(),
              resourceCapacity.getResource().getType(),
              null,
              new Attributes(
                  remainingCapacity,
                  remainingThrough,
                  type
                  )
              ));
    }

    return result;
  }


  public static Types getAllocSpec() {
    Types types = new Types();
    types.addType(
        new AttributesDesc(
            new TypeDesc("The capacity of the storage device.", "float"),
            new TypeDesc("The throughput of the storage device. Either in MB/s or IOPS.", "float"),
            new TypeDesc("The access type of the storage device. One of SEQUENTIAL or RANDOM", "string"))
        );
    types.setMonitor(
        new Monitor(
            new Metric(
                new TypeDesc("Monitor the capacity utilization of the storage devices.", "float"),
                new TypeDesc("Monitor the throughput utilization of the storage devices.", "float")
                )
       ));
    return types;
  }

  public static Resources getResources(
      String schedulerAddress,
      InetSocketAddress[] dirAddresses,
      UserCredentials uc,
      Auth auth,
      Client client) throws IOException {

    freeResourcesResponse freeResources
    = client.getFreeResources(
        schedulerAddress,
        auth,
        uc);

    Resources res = new Resources();

    // random
    res.addResource(
        new Resource(
            "/XtreemFS/"
                + dirAddresses[0].getAddress().getHostAddress()
                + "/storage/random",
                "Storage",
                null,
                new Attributes(
                    freeResources.getRandomCapacity(),
                    freeResources.getRandomThroughput(),
                    AccessTypes.RANDOM
                    )
            ));

    // sequential
    res.addResource(
        new Resource(
            "/XtreemFS/"
                + dirAddresses[0].getAddress().getHostAddress()
                + "/storage/sequential",
                //                        dirAddresses[0].getAddress().getHostAddress(),
                "Storage",
                null,
                new Attributes(
                    freeResources.getStreamingCapacity(),
                    freeResources.getStreamingThroughput(),
                    AccessTypes.SEQUENTIAL
                    )
            ));

    return res;
  }

  public static void releaseAllReservations(
      String schedulerAddress,
      InetSocketAddress[] dirAddresses,
      UserCredentials uc,
      Auth auth,
      Client client) throws IOException {
    MRC.Volumes volumes;
    try {
      volumes = client.listVolumes();
    } catch (IOException e) {
      return;
    }
    for(MRC.Volume v: volumes.getVolumesList()) {
      String volume_name = stripVolumeName(v.getName());
      // first delete the volume
      client.deleteVolume(
          auth,
          uc,
          volume_name);

      // now delete the reservation of rerources
      client.deleteReservation(
          schedulerAddress,
          auth,
          uc,
          volume_name);
    }
  }

  @XmlRootElement(name="ReservationStati")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class ReservationStati implements Serializable {
    private static final long serialVersionUID = -6391605855750581473L;
    public Map<String,ReservationStatus> Instances;
    public ReservationStati() {
      // no-args constructor
    }
    public ReservationStati(Map<String, ReservationStatus> reservationStatus) {
      this.Instances = reservationStatus;
    }
    public Map<String, ReservationStatus> getInstances() {
      return Instances;
    }
    public void setInstances(Map<String, ReservationStatus> reservations) {
      Instances = reservations;
    }
    public void addReservationStatus(String id, ReservationStatus reservation) {
      if (this.Instances == null) {
        this.Instances = new LinkedHashMap<String, LibJSON.ReservationStatus>();
      }
      Instances.put(id, reservation);
    }

  }

  @XmlRootElement(name="ReservationStatus")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class ReservationStatus implements Serializable {
    private static final long serialVersionUID = -5811456962763091947L;
    public boolean Ready;
    public List<String> Address;
//    public List<Resource> AvailableResources;

    public ReservationStatus() {
      // no-args constructor
    }
    public ReservationStatus(boolean ready, String address, List<Resource> availableResources) {
      this.Ready = ready;
      addAddress(address);
//      this.AvailableResources = availableResources;
    }
    public ReservationStatus(boolean ready, String address, Resource availableResource) {
      this.Ready = ready;
      addAddress(address);
//      if (this.AvailableResources == null) {
//        this.AvailableResources = new ArrayList<LibJSON.Resource>();
//      }
//      this.AvailableResources.add(availableResource);
    }
    public boolean isReady() {
      return Ready;
    }
    public void setReady(boolean ready) {
      Ready = ready;
    }
    public void addAddress(String address) {
      if (this.Address == null) {
        this.Address = new ArrayList<String>();
      }
      Address.add(address);
    }
    public List<String> getAddress() {
      return Address;
    }
    public void setAddress(List<String> address) {
      Address = address;
    }
//    public List<Resource> getAvailableResources() {
//      return AvailableResources;
//    }
//    public void setAvailableResources(List<Resource> availableResources) {
//      AvailableResources = availableResources;
//    }
  }

  @XmlRootElement(name="Addresses")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class Addresses implements Serializable {
    private static final long serialVersionUID = -6291321674682669013L;
    public List<String> Addresses;
    public Addresses() {
      // no-args constructor
    }
    public Addresses(List<String> addresses) {
      this.Addresses = addresses;
    }
    public Addresses(String[] addresses) {
      this.Addresses = Arrays.asList(addresses);
    }
    public Addresses(String address) {
      this.Addresses = new ArrayList<String>();
      this.Addresses.add(address);
    }
    public List<String> getAddresses() {
      return Addresses;
    }
    public void setAddresses(List<String> addresses) {
      Addresses = addresses;
    }
  }

  @XmlRootElement(name="ReservationID")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class ReservationID implements Serializable {
    private static final long serialVersionUID = 5629110247326464140L;
    public List<String> ReservationID;
    public ReservationID() {
      // no-args constructor
    }
    public ReservationID(List<String> reservations) {
      this.ReservationID = reservations;
    }
    public ReservationID(String reservation) {
      this.ReservationID = new ArrayList<String>();
      this.ReservationID.add(reservation);
    }
    public List<String> getReservationID() {
      return ReservationID;
    }
    public void setReservationID(List<String> reservations) {
      this.ReservationID = reservations;
    }
    public void addAll(ReservationID reservations) {
      if (this.ReservationID == null) {
        this.ReservationID = reservations.getReservationID();
      }
      else {
        this.ReservationID.addAll(reservations.getReservationID());
      }
    }
  }

  @XmlRootElement(name="Types")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class Types implements Serializable {
    private static final long serialVersionUID = 8877186029978897804L;
    public Map<String, AttributesDesc> Types;
    public Monitor Monitor;
    
    public Types() {
      // no-args constructor
    }
    public void addType(AttributesDesc r) {
      if (Types == null) {
        Types = new LinkedHashMap<String, AttributesDesc>();
      }
      Types.put("Storage", r);
    }
    public Map<String, AttributesDesc> getTypes() {
      return Types;
    }
    public void setTypes(Map<String, AttributesDesc> types) {
      Types = types;
    }
    public Monitor getMonitor() {
      return Monitor;
    }
    public void setMonitor(Monitor monitor) {
      Monitor = monitor;
    }

  }


  @XmlRootElement(name="Monitor")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class Monitor implements Serializable {
    private static final long serialVersionUID = -2350617226171778654L;
    public Map<String, Metric> Metrics;

    public Monitor() {
      // no-args constructor
    }
    
    public Monitor(Metric m) {
      addMetric(m);
    }

    public void addMetric(Metric r) {
      if (Metrics == null) {
        Metrics = new LinkedHashMap<String, Metric>();
      }
      Metrics.put("Storage", r);
    }
    public Map<String, Metric> getMetrics() {
      return Metrics;
    }
    public void setMetrics(Map<String, Metric> types) {
      Metrics = types;
    }
  }

  @XmlRootElement(name="Monitor")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class MonitorCreate implements Serializable {
    private static final long serialVersionUID = -884801953600505842L;
    public Map<String,Type> Storage;
    public int PollTime;
    
    public MonitorCreate() {
      // no-args constructor
    }
    
    public MonitorCreate(String typeName, Type type, int pollTime) {
      addStorage(typeName, type);
      this.PollTime = pollTime;
    }
    public void addStorage(String typeName, Type r) {
      if (Storage == null) {
        Storage = new LinkedHashMap<String, Type>();
      }
      Storage.put(typeName, r);
    }
    public Map<String,Type> getStorage() {
      return Storage;
    }
    public void setStorage(Map<String,Type> types) {
      Storage = types;
    }
  }

  @XmlRootElement(name="Type")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class Type implements Serializable {
      private static final long serialVersionUID = -4680765556555478239L;
      public int PollTimeMultiplier;
      public List<String> Aggregation;
      public Type() {
          // no-args constructor
      }
      public Type(int pollTimeMultiplier, List<String> aggregations) {
          this.PollTimeMultiplier = pollTimeMultiplier;
          this.Aggregation = aggregations;
      }
      public Type(int pollTimeMultiplier, String aggregation) {
        this.PollTimeMultiplier = pollTimeMultiplier;
        addAggregation(aggregation);
      }
      public int getPollTimeMultiplier() {
        return PollTimeMultiplier;
      }
      public void setPollTimeMultiplier(int pollTimeMultiplier) {
        PollTimeMultiplier = pollTimeMultiplier;
      }
      public void addAggregation(String r) {
        if (Aggregation == null) {
          Aggregation = new ArrayList<String>();
        }
        Aggregation.add(r);
      }
      public List<String> getAggregation() {
        return Aggregation;
      }
      public void setAggregation(List<String> aggregation) {
        Aggregation = aggregation;
      }
  }

  @XmlRootElement(name="Metric")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class Metric implements Serializable {
    private static final long serialVersionUID = 5049523121607929911L;
    public TypeDesc CAPACITY_UTILIZATION;
    public TypeDesc THROUGHPUT_UTILIZATION;

    public Metric() {
      // no-args constructor
    }

    public Metric(
        TypeDesc capacity,
        TypeDesc throughput
        ) {
      this.CAPACITY_UTILIZATION = capacity;
      this.THROUGHPUT_UTILIZATION = throughput;
    }

    public TypeDesc getCAPACITY_UTILIZATION() {
      return CAPACITY_UTILIZATION;
    }

    public void setCAPACITY_UTILIZATION(TypeDesc cAPACITY_UTILIZATION) {
      CAPACITY_UTILIZATION = cAPACITY_UTILIZATION;
    }

    public TypeDesc getTHROUGHPUT_UTILIZATION() {
      return THROUGHPUT_UTILIZATION;
    }

    public void setTHROUGHPUT_UTILIZATION(TypeDesc tHROUGHPUT_UTILIZATION) {
      THROUGHPUT_UTILIZATION = tHROUGHPUT_UTILIZATION;
    }
  }
  
  
  @XmlRootElement(name="MetricReq")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class MetricReq implements Serializable {
    private static final long serialVersionUID = -512416243565655226L;
    public String Address;
    public String ReservationID;
    public int Entry;
    public MetricReq() {
      // no-args constructor
    }
    public MetricReq(String reservationID, String addresse, int entry) {
      this.ReservationID = reservationID;
      this.Address = addresse;
      this.Entry = entry;
    }
    public String getReservationID() {
      return ReservationID;
    }
    public void setReservationID(String reservationID) {
      this.ReservationID = reservationID;
    }
    public String getAddress() {
      return Address;
    }
    public void setAddress(String addresses) {
      Address = addresses;
    }
    public int getEntry() {
      return Entry;
    }
    public void setEntry(int entry) {
      Entry = entry;
    }
  }

  @XmlRootElement(name="MetricResp")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class MetricResp implements Serializable {
    private static final long serialVersionUID = 1603978614536722497L;

    public Map<String, String> Metrics;
    public MetricResp() {
      // no-args constructor
    }
    public MetricResp(Map<String, String> metrics) {
      this.Metrics = metrics;
    }
    public Map<String, String> getMetrics() {
      return Metrics;
    }
    public void setMetrics(Map<String, String> metrics) {
      this.Metrics = metrics;
    }
  }

  @XmlRootElement(name="AttributesDesc")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class AttributesDesc implements Serializable {
    private static final long serialVersionUID = -1688672880271364789L;
    public TypeDesc Capacity;
    public TypeDesc Throughput;
    public TypeDesc AccessType;

    public AttributesDesc() {
      // no-args constructor
    }

    public AttributesDesc(
        TypeDesc capacity,
        TypeDesc throughput,
        TypeDesc accessType
        ) {
      this.Capacity = capacity;
      this.Throughput = throughput;
      this.AccessType = accessType;
    }

    public TypeDesc getCapacity() {
      return Capacity;
    }
    public void setCapacity(TypeDesc capacity) {
      Capacity = capacity;
    }
    public TypeDesc getThroughput() {
      return Throughput;
    }
    public void setThroughput(TypeDesc throughput) {
      Throughput = throughput;
    }
    public TypeDesc getAccessType() {
      return AccessType;
    }
    public void setAccessType(TypeDesc accessType) {
      AccessType = accessType;
    }
  }


  @XmlRootElement(name="TypeDesc")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class TypeDesc implements Serializable {
    private static final long serialVersionUID = 9080753186885532769L;
    public String Description;
    public String DataType;

    public TypeDesc() {
      // no-args constructor
    }
    public TypeDesc(
        String description,
        String dataType
        ) {
      this.Description = description;
      this.DataType = dataType;
    }
    public String getDescription() {
      return Description;
    }
    public void setDescription(String description) {
      Description = description;
    }
    public String getDataType() {
      return DataType;
    }
    public void setDataType(String dataType) {
      DataType = dataType;
    }
  }

  @XmlRootElement(name="ResourceCapacity")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class ResourceCapacity implements Serializable {
    private static final long serialVersionUID = -1408331636553103656L;
    public Resource Resource;
    public List<AllocationResource> Allocation;
    public List<ReleaseResource> Release;

    public ResourceCapacity() {
      // no-args constructor
    }

    public ResourceCapacity(Resource resource, List<AllocationResource> reserve, List<ReleaseResource> release) {
      this.Resource = resource;
      this.Allocation = reserve;
      this.Release = release;
    }

    public ResourceCapacity(Resource resource, AllocationResource reserve, ReleaseResource release) {
      this.Resource = resource;
      this.Allocation = new ArrayList<LibJSON.AllocationResource>();
      this.Release = new ArrayList<LibJSON.ReleaseResource>();
      this.Allocation.add(reserve);
      this.Release.add(release);
    }

    public Resource getResource() {
      return Resource;
    }

    public List<AllocationResource> getAllocation() {
      return Allocation;
    }

    public void setAllocation(List<AllocationResource> reserve) {
      Allocation = reserve;
    }

    public List<ReleaseResource> getRelease() {
      return Release;
    }

    public void setRelease(List<ReleaseResource> release) {
      Release = release;
    }
  }

  @XmlRootElement(name="ReserveResource")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class AllocationResource implements Serializable {
    private static final long serialVersionUID = 6310585568556634805L;
    public Attributes Attributes;

    public AllocationResource() {
      // no-args constructor
    }
    public AllocationResource(Attributes attributes) {
      setAttributes(attributes);
    }
    public Attributes getAttributes() {
      return Attributes;
    }
    public void setAttributes(Attributes attributes) {
      this.Attributes = attributes;
    }
  }

  @XmlRootElement(name="ReleaseResource")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class ReleaseResource implements Serializable {
    private static final long serialVersionUID = 8910269518815734323L;
    public Attributes Attributes;

    public ReleaseResource() {
      // no-args constructor
    }
    public ReleaseResource(Attributes attributes) {
      setAttributes(attributes);
    }
    public Attributes getAttributes() {
      return Attributes;
    }
    public void setAttributes(Attributes attributes) {
      this.Attributes = attributes;
    }
  }


  @XmlRootElement(name="Resources")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class Resources implements Serializable {
    private static final long serialVersionUID = 9199843708622395018L;
    public Map<String, Resource> Resources;
    public Resources() {
      // no-args constructor
    }
    public Resources(Resource r) {
      addResource(r);
    }
    public void addResource(Resource r) {
      if (this.Resources == null) {
        this.Resources = new LinkedHashMap<String, Resource>();
      }
      this.Resources.put(r.getID(), r);
    }
    public Map<String, Resource> getResources() {
      return this.Resources;
    }
    public void setResources(Map<String, Resource> resources) {
      this.Resources = resources;
    }
  }

  @XmlRootElement(name="Allocation")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class Allocation implements Serializable {
    private static final long serialVersionUID = 9199843708622395018L;
    public List<Resource> Allocation;
    public MonitorCreate Monitor;
    
    public Allocation() {
      // no-args constructor
    }
    public Allocation(Resource r, MonitorCreate monitor) {
      addResource(r);
      setMonitor(monitor);
    }
    public void addResource(Resource r) {
      if (this.Allocation == null) {
        this.Allocation = new ArrayList<Resource>();
      }
      this.Allocation.add(r);
    }
    public List<Resource> getAllocation() {
      return this.Allocation;
    }
    public void setAllocation(List<Resource> resources) {
      this.Allocation = resources;
    }
    public MonitorCreate getMonitor() {
      return Monitor;
    }
    public void setMonitor(MonitorCreate monitor) {
      Monitor = monitor;
    }
  }



  @XmlRootElement(name="ResourceMapper")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class ResourceMapper implements Serializable {
    private static final long serialVersionUID = 3787591243319192070L;
    public Resource Resource;
    public ResourceMapper() {
      // no-args constructor
    }
    public ResourceMapper(
        Resource resource) {
      this.Resource = resource;
    }
    public Resource getResource() {
      return Resource;
    }
    public void setResource(Resource resource) {
      Resource = resource;
    }
  }

  @XmlRootElement(name="Resource")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class Resource implements Serializable {
    private static final long serialVersionUID = 831790831396236645L;
    public String Group;
    public String Type;
    public String ID;
    public Attributes Attributes;
    public Resource() {
      // no-args constructor
    }
    public Resource(        
        String id,
        String type,
        String group,
        Attributes attributes
       ) {
      this.ID = id;
      this.Type = type;
      this.Group = group;
      this.Attributes = attributes;
    }
    public String getID() {
      return ID;
    }
    public void setID(String iD) {
      ID = iD;
    }

    public String getType() {
      return Type;
    }
    public void setType(String type) {
      Type = type;
    }
    public Attributes getAttributes() {
      return Attributes;
    }
    public void setAttributes(Attributes attributes) {
      Attributes = attributes;
    }
    public String getGroup() {
      return Group;
    }
    public void setGroup(String group) {
      Group = group;
    }
  }

  @XmlRootElement(name="AccessTypes")
  public static enum AccessTypes implements Serializable {
    SEQUENTIAL, RANDOM
  }

  @XmlRootElement(name="Attributes")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class Attributes implements Serializable {
    private static final long serialVersionUID = -5867485593384557874L;
    public double Capacity;
    public double Throughput;
    public AccessTypes AccessType;
    public Attributes() {
      // no-args constructor
    }
    public Attributes(
        double capacity,
        double throughput,
        AccessTypes accessType) {
      // no-args constructor
      this.Capacity = capacity;
      this.Throughput = throughput;
      this.AccessType = accessType;
    }
    public double getCapacity() {
      return Capacity;
    }
    public void setCapacity(double capacity) {
      Capacity = capacity;
    }
    public double getThroughput() {
      return Throughput;
    }
    public void setThroughput(double throughput) {
      Throughput = throughput;
    }
    public AccessTypes getAccessType() {
      return AccessType;
    }
    public void setAccessType(AccessTypes accessType) {
      AccessType = accessType;
    }
  }

  @XmlRootElement(name="Response")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  @JsonSerialize(include=JsonSerialize.Inclusion.NON_NULL)
  public static class Response<E> implements Serializable {
    private static final long serialVersionUID = 4850691130923087378L;
    public E result;
    public Error error;
    public Response() {
      // no-args constructor
    }
    public Response(E result, Error error) {
      this.error = error;
      this.result = result;
    }
    public Response(E result) {
      this.result = result;
      this.error = null;
    }
    public Object getResult() {
      return result;
    }
    public void setResult(E result) {
      this.result = result;
    }
    public Error getError() {
      return error;
    }
    public void setError(Error error) {
      this.error = error;
    }
  }


  @XmlRootElement(name="Error")
  @JsonAutoDetect(fieldVisibility = Visibility.ANY, isGetterVisibility = Visibility.NONE, getterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
  public static class Error implements Serializable {
    private static final long serialVersionUID = -2989036582502777333L;
    public String message;
    public int code;
    public Error() {
      // no-args constructor
    }
    public Error(String message, int code) {
      this.message = message;
      this.code = code;
    }
    public String getMessage() {
      return message;
    }
    public void setMessage(String message) {
      this.message = message;
    }
  }

  public static String getMyAddress() {
    try {
      if (!irmConfig.getHostName().equals("")) {
        return irmConfig.getHostName();
      } else {
        return InetAddress.getLocalHost().getHostAddress();
      }
    } catch (Exception e) {
      return "localhost";
    }
  }
}
