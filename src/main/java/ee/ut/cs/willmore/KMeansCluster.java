package ee.ut.cs.willmore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPMessage;
import org.apache.hama.bsp.BSPPeerProtocol;
import org.apache.hama.bsp.ClusterStatus;
//import org.apache.hama.bsp.DoubleMessage;
import org.apache.zookeeper.KeeperException;

public class KMeansCluster {
	
	static final String CONF_FILE_OUT = "output.";
	static String CONF_MASTER_TASK = "master.task.";
	static String CONF_FILE_SOURCE = "source.";
	

	public static class ClusterBSP extends BSP {
		
		public static final Log LOG = LogFactory.getLog(ClusterBSP.class);
		private Configuration conf;
		private FileSystem fileSys;
		private String masterTask;
		
		static final String POINT_MSG_TAG = "POINTS";

		//Map of peer name => cluster center (mean)
		final Map<String, Point3D> peerMeanMap = new HashMap<String, Point3D>();
		
		//All points currently in my cluster
		final List<Point3D> points = new ArrayList<Point3D>();
				
		@Override
		public void bsp(final BSPPeerProtocol bspPeer) throws IOException,
				KeeperException, InterruptedException {
		
			if (isMaster(bspPeer)) {
				masterInitialize(bspPeer);	
			}
			
			while (true) {
			
				bspPeer.sync();
				
				boolean converged = processMessages(bspPeer);
								
				if (converged) {
					break;
				}
				
				assignmentStep(bspPeer);
				
				updateStep(bspPeer);
				
				
			} 
			
			//Empty inbox as any messages are now unnecessary
			flushReceivedMessages(bspPeer);
			
			writeFinalOutput(bspPeer);	
		}

		private boolean processMessages(BSPPeerProtocol bspPeer) throws IOException {
			
			boolean converged = true;
			
			BSPMessage msg;
			while ((msg = bspPeer.getCurrentMessage()) != null) {
				if (isPointMessage(msg)) {
					addPoints(msg);
				} else if (isMeanMessage(bspPeer, msg)) {
					converged = updateMeanMap(msg) && converged;
				} else {
					throw new RuntimeException("Unknown msg tag: " + new String(msg.getTag()));
				}
			}
			
			
			LOG.info("New Mean Map = " + peerMeanMap);
			
			return converged;
		}

		private boolean updateMeanMap(BSPMessage msg) throws IOException {
			PointMessage pMsg = byteToPointMessage(msg);
			
			boolean converged = pMsg.getData().get(0).equals(peerMeanMap.get(pMsg.getTag()));
			
			peerMeanMap.put(pMsg.getTag(), pMsg.getData().get(0));
			
			return converged;
		}

		private void addPoints(BSPMessage msg) throws IOException {

			points.addAll(byteToPointMessage(msg).getData());
		}

		private boolean isMeanMessage(BSPPeerProtocol bspPeer, BSPMessage msg) {
			//Mean maps use the peer name as the tag
			final String tag = new String(msg.getTag());
			
			for (String peer : bspPeer.getAllPeerNames()) {
				if (peer.equals(tag)) {
					return true;
				}
			}
			
			return false;
		}

		private boolean isPointMessage(BSPMessage msg) {
			return POINT_MSG_TAG.equals(new String(msg.getTag()));
		}

		private void flushReceivedMessages(BSPPeerProtocol bspPeer) throws IOException {
			
			LOG.info("Flushing inbox");
			while(bspPeer.getNumCurrentMessages() > 0) {
				bspPeer.getCurrentMessage();
			}	
		}

		private void writeFinalOutput(final BSPPeerProtocol bspPeer) throws IOException {
			
			
			final String fileName = conf.get(CONF_FILE_OUT) + "/" + bspPeer.getPeerName().replace(":", "_");
			
			LOG.info("Writing final output to: " + fileName);
			
			PointWriter writer = new PointWriter(fileSys.create(new Path(fileName), true));
			
			writer.write(calculateCenter(points));
			writer.write(points);
			
			writer.close();
		}
		
		private static BSPMessage pointToByteMessage(PointMessage pm) throws IOException {
				
			ByteBuffer buffer = ByteBuffer.allocate(pm.getData().size() * 3 * 8);
		    
			for (Point3D p : pm.getData()) {
				buffer.putDouble(p.x);
				buffer.putDouble(p.y);
				buffer.putDouble(p.z);
			}
		   
			return new BSPMessage(pm.getTag().getBytes(), buffer.array());
			
		}
		

		private static PointMessage byteToPointMessage(BSPMessage bMsg) throws IOException {
						
			
			ByteBuffer buffer = ByteBuffer.wrap(bMsg.getData());
			
			List<Point3D> points = new ArrayList<Point3D>();
			
			while (buffer.hasRemaining()) {	
				points.add(new Point3D(buffer.getDouble(), buffer.getDouble(), buffer.getDouble()));
			}
		
			return new PointMessage(new String(bMsg.getTag()), points);			
		}
		


		private void masterInitialize(final BSPPeerProtocol bspPeer) throws IOException {

			LOG.info("Starting Master");
			
			final Path srcFilePath = new Path(conf.get(CONF_FILE_SOURCE));

			if (!fileSys.exists(srcFilePath)) {
				throw new RuntimeException("Could not find source file:" + srcFilePath.getName());
			}
			
			final FSDataInputStream srcFile = fileSys.open(srcFilePath);
			
			final int numPoints = srcFile.readInt();
			
			LOG.info("Number of points is: " + numPoints);
			
			for (int i = 0; i < numPoints; i++) {
				points.add(new Point3D(srcFile.readDouble(), 
									   srcFile.readDouble(), 
									   srcFile.readDouble()));
			}
			
			//Assign one mean to each node
			//Means are chosen "randomly" from points

			final Map<String, Point3D> initPeerMeanMap = new HashMap<String, Point3D>();
			
			int ctr = 0; 
			for (final String peer : bspPeer.getAllPeerNames()) {
				Point3D p = points.get(ctr++);
				initPeerMeanMap.put(peer, p);
			}
			
			// Broadcast all peer => mean pairs
			for (final String peer : bspPeer.getAllPeerNames()) {

				LOG.info("Sending intial means to: " + peer);
				
				for (final Map.Entry<String, Point3D> peerMean : initPeerMeanMap.entrySet()) {
	
					PointMessage msg = new PointMessage(peerMean.getKey(),
							peerMean.getValue());
					bspPeer.send(peer, pointToByteMessage(msg));

				}
			}
			
			LOG.info("Initial point messages sent to peers");
		}


		//Receive my new points and update my mean, notifying peers of change
		private int assignmentStep(final BSPPeerProtocol bspPeer) throws IOException {
						
			//For each of my points, find new best cluster by geometric distance.			
			final Map<String, List<Point3D>> peerNewPoints = new HashMap<String, List<Point3D>>();
			
			for (String peer : peerMeanMap.keySet()) {
				peerNewPoints.put(peer, new ArrayList<Point3D>());
			}
			
			int changeCount = 0;
						
			for (Iterator<Point3D> pointItr = points.iterator(); pointItr.hasNext();) {
				
				final Point3D obs = pointItr.next();
				
				double min = Double.MAX_VALUE;
				String minPeer = null;
				
				for (Map.Entry<String, Point3D> peer : peerMeanMap.entrySet()) {
					double distance = obs.distance(peer.getValue());
					
					if (distance < min) {
						min = distance;
						minPeer = peer.getKey();
					}
				}
				
				if (minPeer.equals(bspPeer.getPeerName())) {
					//I don't send updates for points I already own
					continue;
				}
				
				//Remove the point from my collection as I no longer own it.
				pointItr.remove();
				changeCount += 1;
				peerNewPoints.get(minPeer).add(obs);	
			}
			
			
			//Notify other clusters of new points	
			for (Map.Entry<String, List<Point3D>> peerPoints : peerNewPoints.entrySet()) {
				
				if (peerPoints.getValue().size() == 0) {
					continue;
				}
				
				LOG.info("Send " + peerPoints.getValue().size() + " to " + peerPoints.getKey());
				bspPeer.send(peerPoints.getKey(), 
						     pointToByteMessage(new PointMessage(POINT_MSG_TAG, peerPoints.getValue())));
			}
				
			return changeCount;
		}

	
		/**
		 * Perform the KMeans Update Step. 
		 * {@link http://en.wikipedia.org/wiki/K-means_clustering#Standard_algorithm}
		 * 
		 * Receive PointMessages from peers that notify me of new points
		 * assigned to my cluster. Calculate the new geometric center of my
		 * cluster.
		 * 
		 * @param bspPeer
		 * @throws IOException
		 */
		private void updateStep(BSPPeerProtocol bspPeer) throws IOException {	
				
			LOG.info("My point count is now: " + points.size());
		
			if (0 == points.size()) {
				//Catch initial case where we have no points, and thus can't change our mean.
				return;
			}
			
			broadcastMyMean(bspPeer, calculateCenter(points));
		}
		
		/**
		 * Send my mean as a PointMessage to all peers.
		 * @param bspPeer
		 * @throws IOException
		 */
		private void broadcastMyMean(BSPPeerProtocol bspPeer, Point3D mean) throws IOException {
			
			final BSPMessage msg = pointToByteMessage(new PointMessage(bspPeer.getPeerName(), mean));
			
			for (String peer : bspPeer.getAllPeerNames()) {
				bspPeer.send(peer, msg);
			}
		}

		private Point3D calculateCenter(List<Point3D> points) {
			double x = 0;
			double y = 0;
			double z = 0;
			
			for (Point3D p : points) {
				x += p.x / points.size();
				y += p.y / points.size();
				z += p.z / points.size();
			}
			
			return new Point3D(x, y, z);
		}

		private boolean isMaster(BSPPeerProtocol bspPeer) {
			
			LOG.info("Testing if me (" + bspPeer.getPeerName() + ") is master: " +  
					bspPeer.getPeerName().equals(masterTask));
			
			return bspPeer.getPeerName().equals(masterTask);
		}

		public Configuration getConf() {
			return conf;
		}

		public void setConf(Configuration conf) {
			this.conf = conf;
			this.masterTask = conf.get(CONF_MASTER_TASK);

			try {
				fileSys = FileSystem.get(conf);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	
	public static void main(String[] args) throws InterruptedException,
			IOException, ClassNotFoundException {
		
		if (2 != args.length) {
			System.out.println("Usage: KMeansCluster <num_points> <num_clusters>");
			System.exit(-1);
		}
		
		final int k = Integer.valueOf(args[1]);
		
		// BSP job configuration
		HamaConfiguration conf = new HamaConfiguration();

		BSPJob bsp = new BSPJob(conf, KMeansCluster.class);
		// Set the job name
		bsp.setJobName("K Means Clustering");
		bsp.setBspClass(ClusterBSP.class);

		// Set the task size as a number of GroomServer
		BSPJobClient jobClient = new BSPJobClient(conf);
		ClusterStatus cluster = jobClient.getClusterStatus(true);

		System.out.println("Grooms are: " + cluster.getActiveGroomNames());
		
		// Choose one as a master
		for (String peerName : cluster.getActiveGroomNames().values()) {
			System.out.println("Master Peer:" + peerName);
			conf.set(CONF_MASTER_TASK, peerName);
			break;
		}

		System.out.println("Setting number of tasks / clusters to:" + cluster.getGroomServers());
		
		if (k > cluster.getGroomServers()) {
			System.out.println("Request K of " + k + " is greater than number of grooms " + cluster.getGroomServers());
			System.exit(-1);
		}
		
		bsp.setNumBspTask(cluster.getGroomServers());

		FileSystem fileSys = FileSystem.get(conf);

		final long jobTime = System.currentTimeMillis();

		final String srcFileName = "/tmp/kmeans_" + jobTime + "/random-data-in";
		final String fileOutputDir = "/tmp/kmeans_" + jobTime + "/output";

		final Path srcFilePath = new Path(srcFileName);
		final int numPoints = Integer.valueOf(args[0]);
		
		final int range = 100; //Size of X,Y,Z cube containing points

		new SphereRandomPointGenerator(k, 10).generateSourceFile(fileSys, srcFilePath, numPoints, range);

		conf.set(CONF_FILE_SOURCE, srcFilePath.toString());
		conf.set(CONF_FILE_OUT, fileOutputDir);
		
		System.out.println("Src data at: " + srcFileName);
		System.out.println("Out data at: " + fileOutputDir);
		System.out.println("Starting job");
		
		if (bsp.waitForCompletion(true)) {
			System.out.println("Done!");
		}

		String localOut = "/tmp/" + jobTime + "/local/";

		fileSys.copyToLocalFile(new Path(fileOutputDir), new Path(localOut));

		System.out.println("Output in: " + new Path(localOut));

	}

}

