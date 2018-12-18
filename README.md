# USE AT YOUR OWN RISK!
READ CAREFULLY! 
This repository contains as-is, and not responsible for any lost, damages or issues that may occur.

# CAN-simulator
CAN Simulator is written in Java to simulate CAN Bus class/interfaces communication through serial port. The objects includes CAN Bus, CAN message master/client, communication ports and message listener. This project provides a good example of getting familiar with the concept of CAN Bus.

# ICANBus
ICANBus is the class to represent the CAN Bus and define operations of data read/write on the CAN Bus. It contains CANReader and CANRawMessageConsumer. 

CANReader: it starts a thread to initiate CANRawMessageConsumer which keeps sending active signals to keep the CAN Bus alive.

CANRawMessageConsumer: it is the class which convert the data received from the CAN Bus into CAN Bus file.

# CANClient

CANClient is the interface for the operations on  CAN Bus. 
CANLogEntryNotificationWorker forwards the message events to the listeners

# CANRawMessage
CANRawMessage contains the info needed to create the CANLogEntry

# ICANDataPort 
ICANDataPort is the interface for reading/sending CAN messages onto CAN Bus.

# IRawDataListener
IRawDataListener is the interface to listen the serial port and get the CAN data.

# Communication workflows
The workflow contains three main pipelines: the initial connection workflow, read data workflow and write data workflow.

# Initial Connection Workflow
The connection workflow creates a connection to CAN Bus and keep this connection alive during the communications.
CANBus/establishConnect--> CANBus/startCANReaderThread  --> CANBus/CAN notifier –-> connection done

CANBus/establishConnect: 
establish the connection via a CAN Bus port. There are three connections, which could be TCP/socketCAN/serial.
       For TCP: use remoteConnect() method to establish the connection.
       For socketCAN: use socketCANConnect() method to establish the connection
       For serial port: use secureHandShake() method to establish the connection
       
CANBus/startCANReaderThread: the notifiation/consumer will be created. And it keeps sending sginals to make CAN Bus alive.
CANBus/start: CAN notifier/consumer will be created.

# Read Data Workflow
A queue is created for data receiving/sending, which is called canRAWMessageQueue with customized size.
CANBus/readData(): 
The raw data received will be converted into CANRawMessage and added into canRAWMessageQueue queue.
CANBus/CANRawMessageConsumer gets data from queue: The data read from the canRAWMessageQueue queue will be converted into CANLogEntry and send to internalPublishCANEvent. CANLogEntry will be add into canLogNotificationQueue queue, then notify the CANClient.

# Write Data Workflow
Write data workflow sends data onto CAN Bus.

CANBus/sendCANFrame() --> CANBus/sendData() --> CANBus/writeData() --> ICANDataPort/writeBytes()
CANBus/sendData(): the data will be sent to writeData method.
CANBus/writeData(): a wrapper which calls writeBytes via three communication types,TCP/socketCAN/serial.
ICANDataPort/writeBytes(): the data will be sent to CANBus.

# Simulator hardware example
https://www.youtube.com/watch?v=UffHQoiQ9aU

You can follow up the video to assembly a CAN Bus simulator. It contains a cluster and BCM, which can simulate the door lock/unlock, lights, cluster meters. CAN-simulator codes can be used to communicate the CAN bus simulator through PC.

# Firmware
The CAN Simulator needs to work with firmware to implement the CAN Bus communications.
Let us know if you have any question.
