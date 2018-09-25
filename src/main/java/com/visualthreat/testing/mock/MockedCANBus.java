package com.visualthreat.testing.mock;

import com.visualthreat.platform.common.can.CANLogEntry;
import com.visualthreat.testing.CANClient;
import com.visualthreat.testing.CANEventListener;
import com.visualthreat.testing.ICANBus;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class MockedCANBus implements ICANBus {
  private static final Vector<String> portList = new Vector<String>() {{
    add("mock port");
  }};

  private final Set<CANEventListener> listeners =
      Collections.newSetFromMap(new ConcurrentHashMap<CANEventListener, Boolean>());
  private final CANClient client;
  private final int id;
  private final String logsFolder;
  private final boolean exactMatch;

  private int baudRate = 1000000;
  private boolean connected = false;
  private boolean isListen = false;
  private MockedCANStream canStream;

  public MockedCANBus(String logsFolder, int id, CANClient client, boolean exactMatch) {
    this.id = id;
    this.client = client;
    this.logsFolder = logsFolder;
    this.exactMatch = exactMatch;
  }

  public MockedCANBus(String logsFolder, int id, CANClient client) {
    this(logsFolder, id, client, false);
  }

  @Override
  public void registerListener(CANEventListener listener) {
    listeners.add(listener);
  }

  @Override
  public void unRegisterListener(CANEventListener listener) {
    listeners.remove(listener);
  }

  // used in ExecuteWorkItem only
  @Override
  public void sendCANFrame(CANLogEntry frame) throws IOException {
    canStream.executeFrame(frame);
  }

  @Override
  public void sendCANFrame(int id, int dlc, byte[] data) throws IOException {
    CANLogEntry entry = new CANLogEntry();
    entry.setId(id);
    entry.setDlc(dlc);
    entry.setData(data);
    sendCANFrame(entry);
  }

  @Override
  public void getBaudRate() throws IOException {
    this.client.setInfo("I" + baudRate);
  }

  @Override
  public void closeConnection() {
    DisableListening();
    canStream.setStopped(true);

    this.connected = false;
    this.client.disconnected(this.id);
  }

  @Override
  public boolean connect(String portName) {
    this.connected = true;
    canStream = new MockedCANStream(this, logsFolder, exactMatch);
    this.EnableListening();
    MockedCANStream.start(canStream);
    return true;
  }

  @Override
  public boolean connect(String portName, int speed) {
    return connect(portName);
  }

  @Override
  public void disconnect() {
    if (this.connected) {
      this.client.writeLog(this.id, "Disconnecting CAN Bus ...");
      DisableListening();

      this.closeConnection();
      this.client.writeLog(this.id, "connection disconnected");
    }
  }

  @Override
  public void publishCANEvent(CANLogEntry event) {
    if (isListen) {
      listeners.forEach(listener -> {
        try {
          listener.onEvent(event);
        } catch (Throwable e) {
          this.client.writeLog(this.id, "publishCANEvent failed", e);
        }
      });
    }
  }

  @Override
  public Vector<String> getPortList() {
    this.client.writeLog(this.id, "found the following ports:");

    for (int i = 0; i < portList.size(); ++i) {
      this.client.writeLog(this.id, "   " + portList.elementAt(i));
    }

    return portList;
  }

  @Override
  public void start() throws IOException {
    // this.outputStream.write(START_CMD.getBytes());
  }

  @Override
  public void stop() throws IOException {
    // this.outputStream.write(STOP_CMD.getBytes());
  }

  @Override
  public void setReboot() throws IOException {
    // this.outputStream.write(REBOOT_CMD.getBytes());
  }

  @Override
  public void autoDetectBaudRate() throws IOException {
    // this.outputStream.write(AUTO_DETECT_CMD.getBytes());
  }

  @Override
  public void EnableListening() {
    this.isListen = true;
  }

  @Override
  public void DisableListening() {
    this.isListen = false;
  }

  @Override
  public void setBaudRate(int baudRate) throws IOException {
    this.baudRate = baudRate;
  }

  @Override
  public void startListeners() {}

  @Override
  public boolean isConnected() {
    return connected;
  }

}
