package com.visualthreat.testing;

import com.visualthreat.platform.common.can.CANLogEntry;

import java.io.IOException;
import java.util.AbstractList;

public interface ICANBus {

  AbstractList<String> getPortList();

  boolean connect(String portName);


  boolean connect(String portName, int speed);

  void start() throws IOException;

  void stop() throws IOException;

  void setReboot() throws IOException;

  void getBaudRate() throws IOException;

  void autoDetectBaudRate() throws IOException;

  void EnableListening();

  void DisableListening();

  void registerListener(CANEventListener newListener);

  void unRegisterListener(CANEventListener newListener);


  void publishCANEvent(CANLogEntry event);

  void setBaudRate(int baudRate) throws IOException;


  void sendCANFrame(CANLogEntry frame) throws IOException;


  void sendCANFrame(int id, int dlc, byte[] data) throws IOException;

  void closeConnection();

  void disconnect();

  void startListeners();

  boolean isConnected();
}
