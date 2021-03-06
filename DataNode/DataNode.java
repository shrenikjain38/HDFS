package DataNode;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

import NameNode.INameNode;
import Protobuf.HDFSProtobuf.BlockLocations;
import Protobuf.HDFSProtobuf.BlockReportRequest;
import Protobuf.HDFSProtobuf.BlockReportResponse;
import Protobuf.HDFSProtobuf.DataNodeLocation;
import Protobuf.HDFSProtobuf.HeartBeatRequest;
import Protobuf.HDFSProtobuf.HeartBeatResponse;
import Protobuf.HDFSProtobuf.ReadBlockRequest;
import Protobuf.HDFSProtobuf.ReadBlockResponse;
import Protobuf.HDFSProtobuf.WriteBlockRequest;
import Protobuf.HDFSProtobuf.WriteBlockResponse;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

public class DataNode extends UnicastRemoteObject implements IDataNode {

	private static final long serialVersionUID = 1L;
	private static final String configurationFile = "Resources/datanode.properties";
	private static Integer dataNodeID;
	private static String networkInterface;
	private static Integer heartBeatTimeout;
	private static Integer blockReportTimeout;
	private static File dataDirectory;
	private static String nameNodeLocation;

	public static void main(String[] args) throws IOException {

		if (args.length != 1) {
			System.err.println("USAGE: java DataNode.DataNode <serverID>");
			System.exit(-1);
		}

		dataNodeID = Integer.parseInt(args[0]);

		Properties properties = new Properties();
		InputStream inputStream = new FileInputStream(configurationFile);
		properties.load(inputStream);

		networkInterface = properties.getProperty("Network Interface");
		heartBeatTimeout = Integer.parseInt(properties.getProperty("HeartBeat Timeout"));
		blockReportTimeout = Integer.parseInt(properties.getProperty("BlockReport Timeout"));
		dataDirectory = new File(properties.getProperty("Data Directory"));
		nameNodeLocation = properties.getProperty("NameNode Location");

		if ((networkInterface == null) || (heartBeatTimeout == null) || (blockReportTimeout == null) || (dataDirectory == null) || (nameNodeLocation == null)) {
			System.out.println("Configuration Missing...");
			System.exit(-1);
		}
		System.out.println();

		new Thread(new Runnable() {

			@Override
			public void run() {
				while (true) {
					HeartBeatRequest.Builder heartBeatRequest = HeartBeatRequest.newBuilder();
					heartBeatRequest.setId(dataNodeID);
					INameNode nameNode = null;
					try {
						nameNode = (INameNode) LocateRegistry.getRegistry(nameNodeLocation).lookup("NameNode");
					} catch (RemoteException | NotBoundException e) {
						e.printStackTrace();
					}
					byte[] serializedHeartBeatResponse = null;
					try {
						serializedHeartBeatResponse = nameNode.heartBeat(heartBeatRequest.build().toByteArray());
					} catch (RemoteException e) {
						e.printStackTrace();
					}
					HeartBeatResponse heartBeatResponse = null;
					try {
						heartBeatResponse = HeartBeatResponse.parseFrom(serializedHeartBeatResponse);
					} catch (InvalidProtocolBufferException e) {
						e.printStackTrace();
					}
					Integer heartBeatStatus = heartBeatResponse.getStatus();
					if (heartBeatStatus.equals(1)) {
						System.out.println("Heart Beating...");
					} else {
						System.err.println("Heart not beating properly...");
						System.exit(-1);
					}
					try {
						Thread.sleep(heartBeatTimeout);
					} catch (InterruptedException e) {
						// nope
					}
				}
			}
		}).start();

		new Thread(new Runnable() {

			@Override
			public void run() {
				while (true) {
					File[] blockNumbers = dataDirectory.listFiles(new FileFilter() {

						@Override
						public boolean accept(File pathname) {
							return pathname.isFile() && !pathname.isHidden() && pathname.canRead() && pathname.getName().matches("^-?\\d+$");
						}
					});
					if (blockNumbers == null) {
						System.err.println("Error Reading Data Directory");
						System.exit(-1);
					}
					Inet4Address inetAddress = null;
					try {
						Enumeration<InetAddress> enumeration = NetworkInterface.getByName(networkInterface).getInetAddresses();
						while (enumeration.hasMoreElements()) {
							InetAddress tempInetAddress = enumeration.nextElement();
							if (tempInetAddress instanceof Inet4Address) {
								inetAddress = (Inet4Address) tempInetAddress;
							}
						}
					} catch (SocketException e) {
						e.printStackTrace();
					}
					if (inetAddress == null) {
						System.err.println("Error Obtaining Network Information");
						System.exit(-1);
					}
					BlockReportRequest.Builder blockReportRequest = BlockReportRequest.newBuilder().setId(dataNodeID).setLocation(DataNodeLocation.newBuilder().setIP(inetAddress.getHostAddress()).setPort(Registry.REGISTRY_PORT));
					for (File tempFile : blockNumbers) {
						blockReportRequest.addBlockNumbers(Integer.parseInt(tempFile.getName()));
					}
					INameNode nameNode = null;
					try {
						nameNode = (INameNode) LocateRegistry.getRegistry(nameNodeLocation).lookup("NameNode");
					} catch (RemoteException | NotBoundException e) {
						e.printStackTrace();
					}
					byte[] serializedBlockReportResponse = null;
					try {
						serializedBlockReportResponse = nameNode.blockReport(blockReportRequest.build().toByteArray());
					} catch (RemoteException e) {
						e.printStackTrace();
					}
					BlockReportResponse blockReportResponse = null;
					try {
						blockReportResponse = BlockReportResponse.parseFrom(serializedBlockReportResponse);
					} catch (InvalidProtocolBufferException e) {
						e.printStackTrace();
					}
					for (Integer tempStatus : blockReportResponse.getStatusList()) {
						if (tempStatus == 0) {
							System.err.println("Error in making Block Request Report");
							System.exit(-1);
						}
					}
					try {
						Thread.sleep(blockReportTimeout);
					} catch (InterruptedException e) {
						// nope
					}
				}
			}
		}).start();

		Inet4Address inetAddress = null;
		try {
			Enumeration<InetAddress> enumeration = NetworkInterface.getByName(networkInterface).getInetAddresses();
			while (enumeration.hasMoreElements()) {
				InetAddress tempInetAddress = enumeration.nextElement();
				if (tempInetAddress instanceof Inet4Address) {
					inetAddress = (Inet4Address) tempInetAddress;
				}
			}
		} catch (SocketException e) {
			e.printStackTrace();
		}
		if (inetAddress == null) {
			System.err.println("Error Obtaining Network Information");
			System.exit(-1);
		}

		System.setProperty("java.rmi.server.hostname", inetAddress.getHostAddress());
		try {
			LocateRegistry.createRegistry(Registry.REGISTRY_PORT);
		} catch (ExportException e) {
			System.err.println("Using existing registry...");
		}
		LocateRegistry.getRegistry(inetAddress.getHostAddress(), Registry.REGISTRY_PORT).rebind("DataNode", new DataNode());

		System.out.println("Loaded DataNode...");
	}

	public DataNode() throws RemoteException {
		super();
	}

	@Override
	public byte[] readBlock(byte[] serializedReadBlockRequest) {
		try {
			return ReadBlockResponse.newBuilder().setStatus(1).addData(ByteString.copyFrom(Files.readAllBytes(Paths.get(dataDirectory + "/" + Integer.toString(ReadBlockRequest.parseFrom(serializedReadBlockRequest).getBlockNumber()))))).build().toByteArray();
		} catch (IOException e) {
			e.printStackTrace();
			return ReadBlockResponse.newBuilder().setStatus(0).build().toByteArray();
		}
	}

	@Override
	public byte[] writeBlock(byte[] serializedWriteBlockRequest) {
		try {
			WriteBlockRequest writeBlockRequest = WriteBlockRequest.parseFrom(serializedWriteBlockRequest);
			BlockLocations blockLocations = writeBlockRequest.getBlockInfo();
			Integer blockNumber = blockLocations.getBlockNumber();
			List<DataNodeLocation> dataNodeLocations = blockLocations.getLocationsList();
			Files.write(Paths.get(dataDirectory + "/" + Integer.toString(blockNumber)), ByteString.copyFrom(writeBlockRequest.getDataList()).toByteArray());
			if (blockLocations.getLocationsCount() > 0) {
				DataNodeLocation location = blockLocations.getLocations(0);
				WriteBlockResponse cascadingWriteBlockResponse = WriteBlockResponse.parseFrom(((IDataNode) LocateRegistry.getRegistry(location.getIP(), location.getPort()).lookup("DataNode")).writeBlock(WriteBlockRequest.newBuilder().addAllData(writeBlockRequest.getDataList()).setBlockInfo(BlockLocations.newBuilder().setBlockNumber(blockNumber).addAllLocations(dataNodeLocations.subList(1, dataNodeLocations.size()))).build().toByteArray()));
				if (cascadingWriteBlockResponse.getStatus() == 0) {
					System.err.println("Unable to Cascade Write Requests...");
					return WriteBlockResponse.newBuilder().setStatus(0).build().toByteArray();
				}
			}
			return WriteBlockResponse.newBuilder().setStatus(1).build().toByteArray();
		} catch (IOException | NotBoundException e) {
			e.printStackTrace();
			return WriteBlockResponse.newBuilder().setStatus(0).build().toByteArray();
		}
	}
}
