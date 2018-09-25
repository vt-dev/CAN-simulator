package com.visualthreat.testing;

import static jssc.SerialPortException.TYPE_PORT_NOT_OPENED;

import com.google.common.base.Charsets;
import com.google.common.collect.EvictingQueue;
import com.visualthreat.platform.common.can.CANLogEntry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.xml.bind.DatatypeConverter;
import jssc.SerialNativeInterface;
import jssc.SerialPort;
import jssc.SerialPortEvent;
import jssc.SerialPortEventListener;
import jssc.SerialPortException;
import jssc.SerialPortList;

/**
 * Communication Interface with CAN Bus
 */
public class CANBus implements ICANBus, IRAWDataListener {
  public static final int MAX_LISTENERS = 8;
  public static final int WIFI_SPEED = -1;
  public static final int SOCKETCAN_SPEED = -2;
  public static String STOP_CMD = "Q\r";
  public static String START_CMD = "O\r";
  public static String REBOOT_CMD = "V\r";
  public static String INFO_CMD = "I\r";
  public static String AUTO_DETECT_CMD = "W\r";
  public static List<Integer> supportedBaudDate = new ArrayList<>();
  private Object WRITE_LOCK = new Object();
  private Object READ_LOCK = new Object();
  private static final int REQUEST_WAIT_TIME = 100;
  private static final int TCP_DELAY_TIME = 10000; // in milli-seconds
  private static AtomicLong seqNumGenerator = new AtomicLong(0);


  /**
   * The status of the connection.
   */
  private volatile boolean connected = false;
  /**
   * The Thread used to receive the data from the Serial interface.
   */
  private Thread reader;

  // physical UDB connection
  private SerialPort serialPort = null;

  // remote WIFI connection
  private TCPCANDataPort tcpPort = null;

  // SocketCAN connection
  private SocketCANDataPort socketCANDataPort = null;

  private volatile boolean shutdown = false;


  private CANClient client;

  /**
   * Flag to control if to listen CANBus
   */
  private volatile boolean enableListen = true;

  private int id;

  /**
   * Array of listeners registered
   */
  AtomicReference<CANEventListener>[] canEventListeners = new AtomicReference[MAX_LISTENERS];

  private static final int MAX_CIRCULAR_QUEUE_SIZE = 102400;
  private Queue<CANRawMessage> canRAWMessageQueue = EvictingQueue.create(MAX_CIRCULAR_QUEUE_SIZE);
  private Queue<CANLogEntry> canRequestFrameQueue = new ConcurrentLinkedQueue<>();
  private Map<String, CANLogEntry> canRequestFramesMap = new ConcurrentHashMap<>();
  private BlockingQueue<CANLogEntry> canLogNotificationQueue = new LinkedBlockingQueue<>();
  private volatile boolean hasNewEvents = false;

  /*
   * Initialize supportedBaudRate list for autodetecting baud rate later
   */
  static {
    supportedBaudDate.add(125000);
    supportedBaudDate.add(500000);
    supportedBaudDate.add(10000);
    supportedBaudDate.add(20000);
    supportedBaudDate.add(50000);
    supportedBaudDate.add(100000);
    supportedBaudDate.add(250000);
    supportedBaudDate.add(750000);
    supportedBaudDate.add(1000000);
  }

  public CANBus(int id, CANClient client) {
    this.id = id;
    this.client = client;
    for(int i = 0; i < MAX_LISTENERS; i++) {
      canEventListeners[i] = new AtomicReference<CANEventListener>();
      canEventListeners[i].set(null);
    }
  }


  public CANBus(CANClient client) {
    this(0, client);
  }


  @SuppressWarnings("unchecked")
  public Vector<String> getPortList() {
    String[] portNames = SerialPortList.getPortNames();
    client.writeLog(id, "found the following ports:");
    for (int i = 0; i < portNames.length; i++) {
      client.writeLog(id, ("   " +  portNames[i]));
    }

    Vector<String> result = new Vector<>();
    result.addAll(Arrays.asList(portNames));
    return result;
  }


  public boolean connect(String portName) {
    return connect(portName, 115200);
  }

  public boolean remoteConnect() {
    this.serialPort = null;
    boolean portOpened = false;
    this.tcpPort = new TCPCANDataPort(this);
    try {
      tcpPort.openPort();
      portOpened = true;
    } catch(IOException ex) {
      client.writeLog(id, "Remote TCP CANBus connection could not be made.", ex);
      if(portOpened) {
        try {
          tcpPort.closePort();
        } catch (IOException ignored) {
        }
      }
      tcpPort = null;
      return false;
    }

    client.writeLog(id, "connection on " + this.tcpPort.getPortName() + " established.");
    return true;
  }

  public boolean socketCANConnect(String portName) {
    try {
      this.tcpPort = null;
      this.serialPort = null;
      socketCANDataPort = new SocketCANDataPort(portName, this);
      socketCANDataPort.openPort();
    } catch(IOException ex) {
      client.writeLog(id, "SocketCAN connection could not be made.", ex);
      socketCANDataPort = null;
      return false;
    }
    client.writeLog(id, "connection on " + portName + " established.");
    return true;
  }

  private int getSequenceNumber() {
    long ret = seqNumGenerator.incrementAndGet();
    if(ret >= Integer.MAX_VALUE){
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      seqNumGenerator.set(0);
      return 0;
    }
    return (int)ret;
  }


  public boolean connect(String portName, int speed) {
    boolean conn = false;

    if(speed == WIFI_SPEED) {
      if (!remoteConnect()) {
        return false;
      }
    } else if (speed == SOCKETCAN_SPEED) {
      if(!socketCANConnect(portName)){
        return false;
      }
    }
    try {
      if(this.tcpPort == null && this.socketCANDataPort == null) {
        if (this.serialPort == null) {
          return false;
        }
      }
      // start listener
      CANReader rawCANDataReader = new CANReader();
      reader = (new Thread(rawCANDataReader));
      shutdown = false;
      reader.start();
      if(this.serialPort != null) {
        this.serialPort.addEventListener(rawCANDataReader, SerialPort.MASK_RXCHAR);
      }

      // wait for CAN Message Reader to start
      int i = 0;
      int maxWaitCnt = 12;
      do {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      } while(!connected && ((i++) < maxWaitCnt));
      if(i >= maxWaitCnt) {
        client.writeLog(id, "CANBus connection could not be made.");
        return false;
      }

      if(this.serialPort != null) {
        client.writeLog(id, "connection on " + this.serialPort.getPortName() + " established.");
      }
      conn = true;
    } catch (SerialPortException | IOException e) {
      client.writeLog(id, "CANBus connection could not be made.", e);
    } finally {
      if (conn == false) {
        shutdown = true;
      }
    }
    return conn;
  }

  private void secureHandShake(String portName, int speed)
      throws IOException, SerialPortException {
    String[] portNames;
    SerialNativeInterface serialInterface = new SerialNativeInterface();
    if(serialInterface.getOsType() == serialInterface.OS_WINDOWS) {
      portNames = SerialPortList.getPortNames();
    } else {
      portNames =SerialPortList.getPortNames("/dev/", Pattern.compile("tty\\."));
    }

    for(String curPortName : portNames) {
      if(!curPortName.toLowerCase().startsWith("com") &&
          !curPortName.toLowerCase().startsWith("/dev/tty")){
        continue;
      }
      if(portName != null && portName.length() > 0
          && !portName.equalsIgnoreCase(curPortName)) {
        continue;
      }

      SerialPort curSerialPort = new SerialPort(curPortName);
      try {
        curSerialPort.openPort();
        curSerialPort.setParams(speed,
            SerialPort.DATABITS_8,
            SerialPort.STOPBITS_1,
            SerialPort.PARITY_NONE);
      }
      catch (SerialPortException ignored) {
        continue;
      }

      serialPort = curSerialPort;
      this.serialPort.setRTS(false);
      this.serialPort.setDTR(false);
      this.serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);
      return;
    }
  }

  public void start() throws IOException {
    this.sendData(START_CMD);
  }

  public void stop() throws IOException {
    this.sendData(STOP_CMD);
  }

  public void setReboot() throws  IOException {
    this.sendData(REBOOT_CMD);
  }

  public void getBaudRate() throws  IOException {
    this.sendData(INFO_CMD);
  }

  public void autoDetectBaudRate() throws IOException {
    this.sendData(AUTO_DETECT_CMD);
  }

  public void EnableListening() {
    canRequestFramesMap.clear();
    this.enableListen = true;
  }

  public void DisableListening() {
    this.enableListen = false;
    try {
      Thread.sleep(500);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
    canRequestFramesMap.clear();
  }

  private void sendData(String data) throws IOException {
    try {
      if(!this.shutdown && (this.serialPort != null || this.tcpPort != null
          || this.socketCANDataPort != null)) {
        if(data != null && data.length() > 0) {
          byte[] dataToBeSent = data.getBytes(Charsets.US_ASCII);
          dataToBeSent = Arrays.copyOf(dataToBeSent, dataToBeSent.length);
          writeData(dataToBeSent);
        }
      } else {
        throw new IOException("No CANBus connection");
      }
    } catch (SerialPortException e) {
      throw new IOException(e);
    }
  }

  private void writeData(byte[] dataToBeSent) throws IOException, SerialPortException {
    if(this.shutdown) {
      return;
    }
    synchronized (this.WRITE_LOCK) {
      if (this.tcpPort != null) {
        this.tcpPort.writeBytes(dataToBeSent);
      } else if (this.serialPort != null) {
        this.serialPort.writeBytes(dataToBeSent);
      } else if (this.socketCANDataPort != null) {
        this.socketCANDataPort.writeBytes(dataToBeSent);
      }
    }
  }

  public synchronized void registerListener(CANEventListener newListener) {
    for(AtomicReference<CANEventListener> listenerAtomicReference : canEventListeners){
      CANEventListener listener = listenerAtomicReference.get();
      if(listener == null || listener == newListener) {
        listenerAtomicReference.set(newListener);
        return;
      }
    }
  }

  public synchronized void unRegisterListener(CANEventListener newListener) {
    for(AtomicReference<CANEventListener> listenerAtomicReference : canEventListeners){
      CANEventListener listener = listenerAtomicReference.get();
      if(listener == newListener) {
        listenerAtomicReference.set(null);
        return;
      }
    }
  }

  public void publishCANEvent(CANLogEntry event) {
    event.setTimeStamp(System.currentTimeMillis());
    event.setSeqNum(getSequenceNumber());
    canRequestFrameQueue.offer(event);
    if(tcpPort != null && event != null && event.getType() != CANLogEntry.FrameType.comment) {
      canRequestFramesMap.put(event.toString(), event);
    }
  }

  /**
   * The following function can only be called in a single thread
   * @param event
   */
  private void internalPublishCANEvent(CANLogEntry event) {
    if(this.serialPort != null || this.socketCANDataPort != null) {
      internalUSBPublishCANEvent(event);
    } else if(this.tcpPort != null) {
      internalTcpPublishCANEvent(event);
    }
  }

  /**
   * The following function can only be called in a single thread
   * @param event
   */
  private void internalUSBPublishCANEvent(CANLogEntry event) {
    CANLogEntry requestEntry = canRequestFrameQueue.peek();
    if(event != null) {
      while (requestEntry != null
          && ((requestEntry.getTimeStamp() < event.getTimeStamp())
          || (requestEntry.getTimeStamp() == event.getTimeStamp()
          && requestEntry.getSeqNum() < event.getSeqNum()))){
        canLogNotificationQueue.offer(canRequestFrameQueue.poll());
        requestEntry = canRequestFrameQueue.peek();
      }
      canLogNotificationQueue.offer(event);
    } else {
      long curTimeStamp = System.currentTimeMillis();
      while(requestEntry !=  null && requestEntry.getTimeStamp() < curTimeStamp - REQUEST_WAIT_TIME) {
        canLogNotificationQueue.offer(canRequestFrameQueue.poll());
        requestEntry = canRequestFrameQueue.peek();
      }
    }
  }

  /**
   * The following function can only be called in a single thread
   * @param event
   */
  private void internalTcpPublishCANEvent(CANLogEntry event) {
    CANLogEntry requestEntry = canRequestFrameQueue.peek();
    long curTimeStamp = System.currentTimeMillis();
    if(event != null) {
      if (canRequestFramesMap.containsKey(event.toString())){
        while(!isCANLogEntryEqual(requestEntry, event)) {
          canRequestFrameQueue.poll();
          canRequestFramesMap.remove(requestEntry.toString());
          canLogNotificationQueue.offer(requestEntry);
          requestEntry = canRequestFrameQueue.peek();
        }
        if(requestEntry != null) {
          canRequestFrameQueue.poll();
          canRequestFramesMap.remove(requestEntry.toString());
          canLogNotificationQueue.offer(requestEntry);
        }
      } else {
        canLogNotificationQueue.offer(event);
      }
    } else {
      while(requestEntry !=  null && requestEntry.getTimeStamp() < curTimeStamp - TCP_DELAY_TIME) {
        canLogNotificationQueue.offer(canRequestFrameQueue.poll());
        requestEntry = canRequestFrameQueue.peek();
      }
    }
  }

  private static boolean isCANLogEntryEqual(CANLogEntry left, CANLogEntry right) {
    if (right == null || left == null) return false;
    if (left == right) return true;

    if (right.getId() == left.getId() && right.getDlc() == left.getDlc()) {
      for (int i = 0; i < right.getDlc(); i++) {
        if (left.getData()[i] != right.getData()[i]) {
          return false;
        }
      }
    } else {
      return false;
    }

    return true;
  }


  private void notifyEvent(CANLogEntry event) {
    for(AtomicReference<CANEventListener> listenerAtomicReference : canEventListeners){
      CANEventListener listener = listenerAtomicReference.get();
      if(listener != null) {
        try{
          listener.onEvent(event);
        } catch (Throwable t) {
          client.writeLog(id, "publishCANEvent failed", t);
        }
      }
    }
  }

  public void setBaudRate(int baudRate) throws IOException {
    if (baudRate == 10000) {
      sendData("S0\r");
    } else if (baudRate == 20000) {
      sendData("S1\r");
    } else if (baudRate == 50000) {
      sendData("S2\r");
    } else if (baudRate == 100000) {
      sendData("S3\r");
    } else if (baudRate == 125000) {
      sendData("S4\r");
    } else if (baudRate == 250000) {
      sendData("S5\r");
    } else if (baudRate == 500000) {
      sendData("S6\r");
    } else if (baudRate == 750000) {
      sendData("S7\r");
    } else if (baudRate == 1000000) {
      sendData("S8\r");
    } else {
      throw new IllegalArgumentException("Bitrate not supported");
    }
  }

  public void sendCANFrame(CANLogEntry frame) throws IOException {
    // add type, id, and dlc to string
    String frameStr = String.format("t%03X%d", frame.getId(), frame.getDlc());

    if(frame.getDlc() == 8) {
      frameStr = String.format("%s%02X%02X%02X%02X%02X%02X%02X%02X\r",
          frameStr,
          frame.getData()[0],
          frame.getData()[1],
          frame.getData()[2],
          frame.getData()[3],
          frame.getData()[4],
          frame.getData()[5],
          frame.getData()[6],
          frame.getData()[7]);
    } else {
      // add data bytes to string
      for (int i = 0; i < frame.getDlc(); i++) {
        frameStr = frameStr + String.format("%02X", frame.getData()[i]);
      }

      // add newline (\r) to string
      frameStr = frameStr + '\r';
    }

    sendData(frameStr);
  }

  public void sendCANFrame(int id, int dlc, byte[] data) throws IOException {
    // add type, id, and dlc to string
    String frameStr = String.format("t%03X%d", id, dlc);

    // add data bytes to string
    for (int i = 0; i < data.length; i++) {
      frameStr = frameStr + String.format("%02X", data[i]);
    }

    // add newline (\r) to string
    frameStr = frameStr + '\r';

    sendData(frameStr);
  }

  public void closeConnection() {
    shutdown = true;
    enableListen = false;
    try {
      if(this.serialPort != null) {
        this.serialPort.closePort();
        this.serialPort = null;
      }
      if(this.socketCANDataPort != null) {
        this.socketCANDataPort.closePort();
        this.socketCANDataPort = null;
      }
      if(this.tcpPort != null) {
        this.tcpPort.closePort();
        this.tcpPort = null;
      }
    } catch (SerialPortException | IOException ignored) {
    }
    connected = false;
    client.disconnected(id);
  }

  public void disconnect() {
    if(!connected){
      return;
    }
    client.writeLog(id, "Disconnecting CAN Bus ...");
    enableListen = false;
    shutdown = true;
    try {
      reader.join(1000);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
    closeConnection();
    client.writeLog(id, "connection disconnected");
    canRequestFramesMap.clear();
    return;
  }

  public void startListeners() {

  }

  public boolean isConnected() {
    return connected;
  }

  private int readData(int dataSize) throws SerialPortException {
    if(shutdown || this.serialPort == null) {
      return 0;
    }
    synchronized (this.READ_LOCK) {
      byte[] newData = serialPort.readBytes(dataSize);
      readData(newData);
      return (newData == null) ? 0 : newData.length;
    }
  }

  @Override
  public void readData(byte[] newData) {
    if (enableListen && newData != null && newData.length > 0) {
      hasNewEvents = true;
      canRAWMessageQueue.offer(new CANRawMessage(newData, newData.length, getSequenceNumber()));
    }
  }

  private class CANReader implements Runnable , SerialPortEventListener {
    public static final int HEART_BEAT_INTERVAL = 500;
    public static final int KEEP_ALIVE_MSG_SENT_INTERVAL = 1000;
    private long lastSentHeartBeatTimestamp = 0;

    public void run() {
      final int NO_DATA_TIME_INTERVAL = 500;
      // start notification worker
      Thread notifier = new Thread(new CANLogEntryNotificationWorker());
      notifier.start();
      // start consumer worker
      Thread consumer = new Thread(new CANRawMessageConsumer());
      consumer.start();

      long firstNoDataTimeStamp = 0;
      try {
        while (!shutdown) {
          try {
            if ((serialPort != null && serialPort.getInputBufferBytesCount() < 0) ||
                (tcpPort != null && tcpPort.getInputBufferBytesCount() < 0)) {
              if (firstNoDataTimeStamp == 0) {
                firstNoDataTimeStamp = System.currentTimeMillis();
              } else if (firstNoDataTimeStamp > 0
                  && (firstNoDataTimeStamp + NO_DATA_TIME_INTERVAL) < System.currentTimeMillis()) {
                throw new SerialPortException(serialPort.getPortName(),
                    "ReadData", TYPE_PORT_NOT_OPENED);
              }
            } else {
              firstNoDataTimeStamp = 0;
            }
            Thread.sleep(HEART_BEAT_INTERVAL);
            if(tcpPort != null) {
              long curTimeStamp = System.currentTimeMillis();
              if(curTimeStamp > lastSentHeartBeatTimestamp + KEEP_ALIVE_MSG_SENT_INTERVAL) {
                getBaudRate();
                lastSentHeartBeatTimestamp = curTimeStamp;
              }
            }
          } catch (SerialPortException | IOException e) {
            client.writeLog(id, "connection is closing due to error:", e);
            break;
          } catch (InterruptedException e) {
            client.writeLog(id, "connection is interrupted.", e);
            break;
          }
        }
      } finally {
        closeConnection();
      }
    }

    @Override
    public void serialEvent(SerialPortEvent serialPortEvent) {
      if(serialPortEvent.isRXCHAR() && serialPortEvent.getEventValue() > 0){
        try {
          readData(serialPortEvent.getEventValue());
        } catch (SerialPortException e) {
          client.writeLog(id, "connection is closing due to error:", e);
          shutdown = true;
        }
      }
    }
  }

  private class CANRawMessageConsumer implements Runnable {
    public static final int MAX_CAN_MESSAGE_LEN = 26;
    public static final int MIN_CAN_MESSAGE_LEN = 4;

    @Override
    public void run() {
      client.writeLog(id, "CANRawMessageConsumer is starting...");
      long receivedCANFramesCount = 0;
      int NO_DATA_WAIT_TIME_INTERVAL = 20;
      StringBuilder sb = new StringBuilder(20480);
      connected = true;
      try {
        while (!shutdown) {
          CANRawMessage msg = canRAWMessageQueue.poll();
          if (msg == null) {
            internalPublishCANEvent(null);
            Thread.sleep(NO_DATA_WAIT_TIME_INTERVAL);
            continue;
          }

          byte[] buffer = msg.getData();
          for (int i = 0; i < buffer.length; i++) {
            byte tmpByte = buffer[i];
            if (tmpByte < 0) {
              tmpByte += 256;
            }
            char tmpChar = (char) tmpByte;
            if (tmpChar == '\r' || tmpChar == '\n') {
              //parse frame
              try {
                if (sb.length() < MIN_CAN_MESSAGE_LEN || sb.length() > MAX_CAN_MESSAGE_LEN) {
                  sb.setLength(0);
                  continue;
                }
                String firstChar = sb.substring(0, 1);
                if (!firstChar.equals("t") && !firstChar.equals("r")) {
                  if (firstChar.equals("I")) {
                    client.setInfo(sb.toString());
                  }
                  sb.setLength(0);
                  continue;
                }

                int offset = 0;
                CANLogEntry logEntry = new CANLogEntry();
                logEntry.setSeqNum((int)msg.getSeqNum());
                logEntry.setTimeStamp(msg.getTimeStamp());
                if(firstChar.equals("r")) {
                  offset = 5;
                }

                logEntry.setId(Integer.valueOf(sb.substring(1, 4 + offset), 16));

                logEntry.setDlc(Integer.valueOf(sb.substring(4 + offset, 5 + offset), 10));

                if (logEntry.getDlc() > 8 || sb.length() < logEntry.getDlc() * 2 + 5) {
                  sb.setLength(0);
                  continue;
                }
                logEntry.setData(DatatypeConverter.parseHexBinary(
                    sb.substring(5 + offset, 5 + offset + logEntry.getDlc() * 2)));

                sb.setLength(0);
                internalPublishCANEvent(logEntry);

                receivedCANFramesCount++;
                if (receivedCANFramesCount % 30000 == 0) {
                  client.writeLog(id,
                      String.format("Received CAN Count=%d, Notification Queue Length=%d",
                          receivedCANFramesCount, canLogNotificationQueue.size()));
                }
              } catch (StringIndexOutOfBoundsException ex) {
                client.writeLog(id, "Found invalid CAN Message=" + sb.toString());
                sb.setLength(0);
                continue;
              } catch (IllegalArgumentException ile) {
                client.writeLog(id, "Got wrong frame data=" + sb.toString());
                sb.setLength(0);
                continue;
              }
            } else {
              sb.append(tmpChar);
            }
          }
        }
      } catch (InterruptedException ignored) {
      } finally {
        client.writeLog(id, "CANRawMessageConsumer exiting...");
        shutdown = true;
      }
    }
  }

  /**
   * Publish Event Notification to Registered Listeners
   */
  private class CANLogEntryNotificationWorker implements Runnable {
    @Override
    public void run() {
      try {
        client.writeLog(id, "CANLogEntryNotificationWorker is starting...");
        while (!shutdown) {
          CANLogEntry entry = canLogNotificationQueue.poll(200, TimeUnit.MILLISECONDS);
          if (entry != null) {
            entry.setSeqNum(0);
            notifyEvent(entry);
          }
        }
      }catch(Throwable t) {
        if(t instanceof InterruptedException){
          Thread.currentThread().interrupt();
        } 
      } finally {
        shutdown = true;
      }
    }
  }
}
