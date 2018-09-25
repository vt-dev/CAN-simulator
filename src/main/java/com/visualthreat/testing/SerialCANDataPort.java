package com.visualthreat.testing;

import java.io.IOException;

import jssc.SerialPort;
import jssc.SerialPortException;
import lombok.extern.slf4j.Slf4j;

/**
 * Supports to write / read data to / from a serial CAN data port
 */
@Slf4j
public class SerialCANDataPort implements ICANDataPort {
  private SerialPort serialPort;

  public SerialCANDataPort(SerialPort inSerialPort) {
    this.serialPort = inSerialPort;
  }

  @Override
  public boolean writeBytes(byte[] buffer) throws IOException {
    try {
      return this.serialPort.writeBytes(buffer);
    } catch (SerialPortException e) {
      throw new IOException(e);
    }
  }

  @Override
  public byte[] readBytes(int byteCount) throws IOException {
    try {
      return this.serialPort.readBytes(byteCount);
    } catch (SerialPortException e) {
      throw new IOException(e);
    }
  }

  @Override
  public int getInputBufferBytesCount() throws IOException {
    try {
      return this.serialPort.getInputBufferBytesCount();
    } catch (SerialPortException e) {
      throw new IOException(e);
    }
  }

  @Override
  public boolean openPort() throws IOException {
    try {
      return this.serialPort.openPort();
    } catch (SerialPortException e) {
      throw new IOException(e);
    }
  }

  @Override
  public boolean closePort() throws IOException {
    try {
      if(this.serialPort != null) {
        boolean result = this.serialPort.closePort();
        this.serialPort = null;
        return result;
      }
    } catch (SerialPortException e) {
      throw new IOException(e);
    }
    return false;
  }

  @Override
  public String getPortName() {
    return this.serialPort.getPortName();
  }
}
