package com.visualthreat.testing;

public interface CANClient {

  public void disconnected(int id);


  public void writeLog(int id, String text);
  public void writeLog(int id, String text, Throwable t);

  /**
   * CAN Bus Returns Info Msg for Client
   * @param info
   */
  public void setInfo(String info);
}
