/***
 * Coalevo Project
 * http://www.coalevo.net
 *
 * (c) Dieter Wimberger
 * http://dieter.wimpi.net
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at:
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ***/
package net.wimpi.crowd.radius;

import com.atlassian.crowd.integration.rest.service.factory.RestCrowdClientFactory;
import com.atlassian.crowd.model.user.User;
import com.atlassian.crowd.service.client.ClientProperties;
import com.atlassian.crowd.service.client.ClientPropertiesImpl;
import com.atlassian.crowd.service.client.CrowdClient;
import net.wimpi.crowd.radius.util.FastByteArrayInputStream;
import net.wimpi.crowd.radius.util.FastByteArrayOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.tinyradius.attribute.RadiusAttribute;
import org.tinyradius.attribute.StringAttribute;
import org.tinyradius.packet.AccessRequest;
import org.tinyradius.packet.RadiusPacket;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;

/**
 * Provides a more scaleable Radius Server implementation with
 * a multi-threaded design.
 * <p/>
 *
 * @author Dieter Wimberger
 * @version @version@ (@date@)
 */
class RadiusService {

  private static final Marker c_LogMarker = MarkerFactory.getMarker(RadiusService.class.getName());
  private static final Logger c_Log = LoggerFactory.getLogger(RadiusService.class.getName());
  private static final ResourceBundle c_ResourceBundle =
      ResourceBundle.getBundle("net.wimpi.crowd.radius.strings");

  
  private InetAddress m_ListenAddress = null;
  private int m_AuthPort = 1812;
  private DatagramSocket m_AuthSocket = null;
  private InetSocketAddress m_LocalAddress = null;
  private List<ReceivedPacket> m_ReceivedPackets = new LinkedList<ReceivedPacket>();
  private long m_DuplicateInterval = 30000; // 30 s
  private int m_NumberOfThreads = 5;
  private ClientProperties m_CrowdClientProperties;
  private CrowdClient m_CrowdClient;

  private PacketReceiver m_PacketReceiver;
  private byte[] m_Buffer = new byte[RadiusPacket.MAX_PACKET_LENGTH];
  private FastByteArrayOutputStream m_Output = new FastByteArrayOutputStream(m_Buffer);
  private Map<String, String> m_SharedSecrets;
  private AtomicBoolean m_Started = new AtomicBoolean(false);
  private ExecutorService m_ExecutorService;

  /**
   * Returns the shared secret used to communicate with the client with the
   * passed IP address or null if the client is not allowed at this server.
   *
   * @param client IP address and port number of client
   * @return shared secret or null
   */
  public String getSharedSecret(InetSocketAddress client) {
    String key = client.getAddress().getHostAddress();
    c_Log.debug(c_LogMarker, "Retrieving secret for client " + key + " isavail=" + m_SharedSecrets.containsKey(key));
    Object o = m_SharedSecrets.get(key);
    return ((o != null) ? o.toString() : null);
  }//getSharedSecret

  public void start() throws Exception {
    if (!m_Started.get()) {
      //prepare socket
      prepareAuthenticationSocket();
      m_LocalAddress = (InetSocketAddress) m_AuthSocket.getLocalSocketAddress();

      //prepare executor
      m_ExecutorService = new ScheduledThreadPoolExecutor(m_NumberOfThreads);


      m_CrowdClient = new RestCrowdClientFactory().newInstance(m_CrowdClientProperties);
      m_CrowdClient.testConnection();

      //start packet receiver
      m_PacketReceiver = new PacketReceiver();
      Thread receiver = new Thread(m_PacketReceiver);
      receiver.start();
      m_Started.set(true);
    }
    c_Log.info(c_LogMarker, c_ResourceBundle.getString("RadiusService.started"));
  }//start

  public void stop() {
    if (m_Started.get()) {
      m_PacketReceiver.stop();
      m_Started.set(false);
      m_CrowdClient.shutdown();
    }
    c_Log.info(c_LogMarker, c_ResourceBundle.getString("RadiusService.stopped"));
  }//stop

  public void configure(Properties conf) {
    c_Log.debug(c_LogMarker, "updated(Properties)" + ((conf == null) ? "NULL" : conf.toString()));
    if (conf == null) {
      return;
    }
    try {
      //Note: if the conf is null, then the dictionary will automatically return
      //defaults for all values.

      m_NumberOfThreads = Integer.parseInt(conf.getProperty(NUMBEROFTHREAD_KEY));

      m_CrowdClientProperties = ClientPropertiesImpl.newInstanceFromProperties(conf);

      boolean started = m_Started.get();
      stop();
      m_ListenAddress = InetAddress.getByName(conf.getProperty(INTERFACE_KEY));
      m_AuthPort = Integer.parseInt(conf.getProperty(PORT_KEY));
      //keys
      m_SharedSecrets = new HashMap<String, String>();
      String css = conf.getProperty(CLIENTS_KEY);
      if (css != null && css.length() > 0) {
        String[] clients = css.split(",");
        for (int i = 0; clients != null && i < clients.length; i++) {
          String[] client = clients[i].split(":");
          m_SharedSecrets.put(client[0], client[1]);
          c_Log.debug(c_LogMarker, "Added shared secret for client " + client[0]);
        }
      }
      m_DuplicateInterval = Long.parseLong(conf.getProperty(DUPLICATEINTERVAL_KEY));
      c_Log.info(c_ResourceBundle.getString("RadiusService.configured"));
      if (started) {
        start();
      }
    } catch (UnsupportedOperationException ex) {
      c_Log.error(c_LogMarker, c_ResourceBundle.getString("RadiusService.exception.nometatype"), ex);
      System.exit(1);
    } catch (Exception ex) {
      c_Log.error(c_LogMarker, c_ResourceBundle.getString("RadiusService.exception.start"), ex);
      System.exit(1);
    }
  }//configure

  /**
   * Returns the auth port the server will listen on.
   *
   * @return auth port
   */
  public int getAuthPort() {
    return m_AuthPort;
  }//getAuthPort

  /**
   * Sets the auth port the server will listen on.
   *
   * @param authPort auth port, 1-65535
   */
  public void setAuthPort(int authPort) {
    if (authPort < 1 || authPort > 65535)
      throw new IllegalArgumentException("bad port number");
    this.m_AuthPort = authPort;
    this.m_AuthSocket = null;
  }//setAuthPort

  /**
   * Returns the duplicate interval in ms.
   * A packet is discarded as a duplicate if in the duplicate interval
   * there was another packet with the same identifier originating from the
   * same address.
   *
   * @return duplicate interval (ms)
   */
  public long getDuplicateInterval() {
    return m_DuplicateInterval;
  }//getDuplicateInterval

  /**
   * Sets the duplicate interval in ms.
   * A packet is discarded as a duplicate if in the duplicate interval
   * there was another packet with the same identifier originating from the
   * same address.
   *
   * @param duplicateInterval duplicate interval (ms), >0
   */
  public void setDuplicateInterval(long duplicateInterval) {
    if (duplicateInterval <= 0) {
      throw new IllegalArgumentException("duplicate interval must be positive");
    }
    this.m_DuplicateInterval = duplicateInterval;
  }//setDuplicateInterval

  /**
   * Returns the IP address the server listens on.
   * Returns null if listening on the wildcard address.
   *
   * @return listen address or null
   */
  public InetAddress getListenAddress() {
    return m_ListenAddress;
  }//getListenAddress

  /**
   * Sets the address the server listens on.
   * Must be called before start().
   * Defaults to null, meaning listen on every
   * local address (wildcard address).
   *
   * @param listenAddress listen address or null
   */
  public void setListenAddress(InetAddress listenAddress) {
    this.m_ListenAddress = listenAddress;
  }//setListenAddress

  /**
   * Copies all Proxy-State attributes from the request
   * packet to the response packet.
   *
   * @param request request packet
   * @param answer  response packet
   */
  protected void copyProxyState(RadiusPacket request, RadiusPacket answer) {
    List proxyStateAttrs = request.getAttributes(33);
    for (Iterator i = proxyStateAttrs.iterator(); i.hasNext();) {
      RadiusAttribute proxyStateAttr = (RadiusAttribute) i.next();
      answer.addAttribute(proxyStateAttr);
    }
  }//copyProxyState


  /**
   * Constructs an answer for an Access-Request packet.
   * This implementation will only work for PAP.
   *
   * @param accessRequest Radius request packet
   * @return response packet or null if no packet shall be sent
   */
  private RadiusPacket accessRequestReceived(AccessRequest accessRequest) {

    boolean authentic = false;
    List<String> roles = null;
    try {
      //1. authenticate
      User u = m_CrowdClient.authenticateUser(accessRequest.getUserName(), accessRequest.getUserPassword());
      //Produces too much data for many users
      //roles = m_CrowdClient.getNamesOfGroupsForUser(u.getName(),0,-1);
      authentic = true;
    } catch (Exception ex) {
      c_Log.error(c_LogMarker, "accessRequestReceived()");
    }
    final int type = (authentic) ? RadiusPacket.ACCESS_ACCEPT : RadiusPacket.ACCESS_REJECT;
    RadiusPacket answer = new RadiusPacket(type, accessRequest.getPacketIdentifier());
    if (roles != null) {
      //roles into string
      StringBuilder sbuf = new StringBuilder();
      for (Iterator<String> iter = roles.iterator(); iter.hasNext();) {
        sbuf.append(iter.next());
        if (iter.hasNext()) {
          sbuf.append(',');
        }
      }
      RadiusAttribute attr = new StringAttribute(36, sbuf.toString());
      answer.addAttribute(attr);
    }
    copyProxyState(accessRequest, answer);
    return answer;
  }//accessRequestReceived

  /**
   * Prepares and binds the authentication socket.
   *
   * @throws SocketException if the socket cannot be prepared.
   */
  protected void prepareAuthenticationSocket()
      throws SocketException {
    if (m_AuthSocket == null) {
      if (getListenAddress() == null) {
        InetAddress listen = null;
        try {
          listen = getLocalPreferrablyNonLoopback();
        } catch (Exception e) {
          c_Log.error(c_LogMarker, "prepareAuthenticationSocket()", e);
        }
        if (listen != null) {
          m_AuthSocket = new DatagramSocket(getAuthPort(), listen);
        } else {
          m_AuthSocket = new DatagramSocket(getAuthPort());
        }
      } else {
        m_AuthSocket = new DatagramSocket(getAuthPort(), getListenAddress());
      }
      m_AuthSocket.setSoTimeout(0);
    }
  }//prepareAuthenticationSocket

  /**
   * Creates a Radius response datagram packet from a RadiusPacket to be send.
   *
   * @param packet  RadiusPacket
   * @param secret  shared secret to encode packet
   * @param address where to send the packet
   * @param port    destination port
   * @param request request packet
   * @throws IOException it the io fails.
   */
  private void sendDatagramPacket(RadiusPacket packet, String secret, InetAddress address, int port,
                                  RadiusPacket request)
      throws IOException {
    synchronized (m_Output) {
      m_Output.reset();
      packet.encodeResponsePacket(m_Output, secret, request);
      m_AuthSocket.send(new DatagramPacket(m_Output.getBuffer(), m_Output.size(), address, port));
      notifyAll();
    }
  }//makeDatagramPacket

  /**
   * Checks whether the passed packet is a duplicate.
   * A packet is duplicate if another packet with the same identifier
   * has been sent from the same host in the last time.
   *
   * @param packet  packet in question
   * @param address client address
   * @return true if it is duplicate
   */
  protected boolean isPacketDuplicate(RadiusPacket packet, InetSocketAddress address) {
    long now = System.currentTimeMillis();
    long intervalStart = now - getDuplicateInterval();

    byte[] authenticator = packet.getAuthenticator();

    for (Iterator i = m_ReceivedPackets.iterator(); i.hasNext();) {
      ReceivedPacket p = (ReceivedPacket) i.next();
      if (p.receiveTime < intervalStart) {
        // packet is older than duplicate interval
        i.remove();
      } else {
        if (p.address.equals(address) && p.packetIdentifier == packet.getPacketIdentifier()) {
          return !(authenticator != null && p.authenticator != null) || Arrays.equals(p.authenticator, authenticator);
        }
      }
    }

    // add packet to receive list
    ReceivedPacket rp = new ReceivedPacket();
    rp.address = address;
    rp.packetIdentifier = packet.getPacketIdentifier();
    rp.receiveTime = now;
    rp.authenticator = authenticator;
    m_ReceivedPackets.add(rp);
    return false;
  }//isPacketDuplicate

  class PacketHandler
      implements Runnable {

    private RadiusPacket m_Packet;
    private InetSocketAddress m_RemoteAddress;

    public PacketHandler(RadiusPacket rp, InetSocketAddress raddr) {
      m_Packet = rp;
      m_RemoteAddress = raddr;
    }//constructor


    public void run() {

      try {

        // handle packet
        RadiusPacket response = null;

        // check for duplicates
        if (!isPacketDuplicate(m_Packet, m_RemoteAddress)) {
          if (m_LocalAddress.getPort() == getAuthPort()) {
            // handle packets on auth port
            if (m_Packet instanceof AccessRequest) {
              response = accessRequestReceived((AccessRequest) m_Packet);
            } else {
              c_Log.error(c_LogMarker, "unknown Radius packet type: " + m_Packet.getPacketType());
            }
          }
        } else {
          c_Log.info(c_LogMarker, "ignore duplicate packet");
        }
        // send response
        if (response != null) {
          if (c_Log.isDebugEnabled()) {
            c_Log.debug(c_LogMarker, "send response: " + response + " to " + m_RemoteAddress.toString());
          }
          sendDatagramPacket(response, getSharedSecret(m_RemoteAddress), m_RemoteAddress.getAddress(), m_RemoteAddress.getPort(), m_Packet);

        } else {
          c_Log.info(c_LogMarker, "no response sent");
        }
      } catch (IOException ioex) {
        c_Log.error(c_LogMarker, "run()::I/O Exception", ioex);
      }
    }//run

  }//PacketHandler

  class PacketReceiver
      implements Runnable {

    private Marker m_LogMarker = MarkerFactory.getMarker(PacketReceiver.class.getName());
    private AtomicBoolean m_Continue = new AtomicBoolean(true);
    private CountDownLatch m_WaitStop;
    private byte[] m_Buffer = new byte[RadiusPacket.MAX_PACKET_LENGTH];
    private FastByteArrayInputStream m_Input = new FastByteArrayInputStream(m_Buffer);

    public void run() {
      do {
        try {
          //1. Receive and decode package
          final DatagramPacket packet = new DatagramPacket(m_Buffer, RadiusPacket.MAX_PACKET_LENGTH);
          m_AuthSocket.receive(packet);
          m_Input.reset(packet.getLength());
          final InetSocketAddress remoteAddress =
              new InetSocketAddress(packet.getAddress(), packet.getPort());
          final String secret = getSharedSecret(remoteAddress);
          if (secret == null) {
            if (c_Log.isDebugEnabled()) {
              c_Log.debug(m_LogMarker, "PacketReceiver()::Ignoring packet from unknown client " + remoteAddress.toString() + " received on local address " + m_LocalAddress);
            }
            continue;
          }
          final RadiusPacket rp = RadiusPacket.decodeRequestPacket(m_Input, getSharedSecret(remoteAddress));
          //2. execute the handler using the pool
          m_ExecutorService.execute(new PacketHandler(rp, remoteAddress));

          if (c_Log.isDebugEnabled()) {
            c_Log.debug(m_LogMarker, "PacketReceiver()::run()::Received radius packet to queue.");
          }
        } catch (Exception ex) {
          if (m_Continue.get()) {
            c_Log.error(m_LogMarker, "PacketReceiver()::run()", ex);
          }
          if (m_AuthSocket.isClosed()) {
            m_Continue.set(false);
          }
        }
      } while (m_Continue.get());
      m_WaitStop.countDown();
    }//run

    public void stop() {
      m_Continue.set(false);
      m_WaitStop = new CountDownLatch(1);
      try {
        m_AuthSocket.close();
      } catch (Exception ex) {

      }
      try {
        m_WaitStop.await();
      } catch (InterruptedException e) {
        c_Log.error(m_LogMarker, "PacketReceiver::stop()", e);
      }
    }//stop

  }//PacketReceiver


  /**
   * This internal class represents a packet that has been received by
   * the server.
   */
  class ReceivedPacket {

    /**
     * The identifier of the packet.
     */
    public int packetIdentifier;

    /**
     * The time the packet was received.
     */
    public long receiveTime;

    /**
     * The address of the host who sent the packet.
     */
    public InetSocketAddress address;

    /**
     * Authenticator of the received packet.
     */
    public byte[] authenticator;

  }//inner class Received packet

  public static InetAddress getLocalPreferrablyNonLoopback() throws Exception {
    Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
    InetAddress iad = InetAddress.getByName("127.0.0.1");
    while (nis.hasMoreElements()) {
      NetworkInterface ni = nis.nextElement();

      Enumeration<InetAddress> addresses = ni.getInetAddresses();
      while (addresses.hasMoreElements()) {
        InetAddress addr = addresses.nextElement();
        if (!addr.equals(iad)) {
          return addr;
        }
      }//while
    }//while
    return iad;
  }//getLocalPreferrablyNonLoopback

  public static final String INTERFACE_KEY = "server.interface";
  public static final String PORT_KEY = "server.port";
  public static final String CLIENTS_KEY = "server.clients";
  public static final String DUPLICATEINTERVAL_KEY = "server.duplicate.interval";
  public static final String NUMBEROFTHREAD_KEY = "server.responsethreads.number";

}//class RadiusService
