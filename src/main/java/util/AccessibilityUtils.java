package util;

import org.apache.log4j.Logger;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureReader;
import org.geotools.geopkg.FeatureEntry;
import org.geotools.geopkg.GeoPackage;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.SpatialIndex;
import org.locationtech.jts.index.quadtree.Quadtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.IdSet;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.utils.misc.Counter;
import org.opengis.feature.simple.SimpleFeature;
import resources.Properties;
import resources.Resources;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AccessibilityUtils {

    private final static Logger log = Logger.getLogger(AccessibilityUtils.class);
    private final static GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    public static Geometry readBoundary(String filePath) throws IOException {
        GeoPackage geopkg = new GeoPackage(openFile(filePath));
        SimpleFeatureReader r = geopkg.reader(geopkg.features().get(0), null,null);
        SimpleFeature f = r.next();
        Geometry boundary = (Geometry) f.getDefaultGeometry();
        r.close();
        geopkg.close();
        return boundary;
    }

    private static File openFile(String filePath) {
        File file = new File(filePath);
        if(!file.exists()) {
            throw new RuntimeException("File " + filePath + " not found!");
        }
        return file;
    }

    public static void writeFeaturesToGpkg(SimpleFeatureCollection collection, String description, String outputFilePath) throws IOException {
        log.info("Writing features...");
        File outputFile = new File(outputFilePath);
        if(outputFile.delete()) {
            log.warn("File " + outputFile.getAbsolutePath() + " already exists. Overwriting.");
        }
        GeoPackage out = new GeoPackage(outputFile);
        out.init();
        FeatureEntry entry = new FeatureEntry();
        entry.setDescription(description);
        out.add(entry,collection);
        out.createSpatialIndex(entry);
        out.close();
    }

    public static Map<SimpleFeature, IdSet<Node>> assignNodesToZones(Collection<SimpleFeature> zones, Set<Id<Node>> nodeIds, Network network) {
        log.info("Assigning nodeIds to polygon features...");
        SpatialIndex zonesQt = createQuadtree(zones);
        Map<SimpleFeature, IdSet<Node>> nodesPerZone = new HashMap<>(zones.size());
        Counter counter = new Counter("Processing node "," / " + nodeIds.size());
        for (Id<Node> nodeId : nodeIds) {
            counter.incCounter();
            SimpleFeature z = findZone(network.getNodes().get(nodeId).getCoord(),zonesQt);
            if (z != null) {
                nodesPerZone.computeIfAbsent(z, k -> new IdSet<>(Node.class)).add(nodeId);
            } else {
                log.warn("No polygon contains nodeId " + nodeId.toString());
            }
        }
        return Collections.unmodifiableMap(nodesPerZone);
    }


    private static SpatialIndex createQuadtree(Collection<SimpleFeature> features) {
        log.info("Creating spatial index");
        SpatialIndex zonesQt = new Quadtree();
        Counter counter = new Counter("Indexing zone "," / " + features.size());
        for (SimpleFeature feature : features) {
            counter.incCounter();
            Geometry geom = (Geometry) (feature.getDefaultGeometry());
            if(!geom.isEmpty()) {
                Envelope envelope = ((Geometry) (feature.getDefaultGeometry())).getEnvelopeInternal();
                zonesQt.insert(envelope, feature);
            } else {
                throw new RuntimeException("Null geometry for zone " + feature.getID());
            }
        }
        return zonesQt;
    }

    private static SimpleFeature findZone(Coord coord, SpatialIndex zonesQt) {
        Point pt = GEOMETRY_FACTORY.createPoint(new Coordinate(coord.getX(), coord.getY()));
        List elements = zonesQt.query(pt.getEnvelopeInternal());
        for (Object o : elements) {
            SimpleFeature z = (SimpleFeature) o;
            if (((Geometry) z.getDefaultGeometry()).intersects(pt)) {
                return z;
            }
        }
        return null;
    }

    public static Network readModeSpecificNetwork(String transportMode) {

        // Read network
        log.info("Reading MATSim network...");
        String networkPath = Resources.instance.getString(resources.Properties.NETWORK);
        Network fullNetwork = NetworkUtils.createNetwork();
        new MatsimNetworkReader(fullNetwork).readFile(networkPath);

        // Filter to specific mode
        Network modeSpecificNetwork = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(fullNetwork).filter(modeSpecificNetwork, Collections.singleton(transportMode));
        NetworkUtils.runNetworkCleaner(modeSpecificNetwork);
        return modeSpecificNetwork;

    }

    public static Set<Id<Node>> getNodesInBoundary(Network network, Geometry boundary) {
        ConcurrentLinkedQueue<Node> allNodes = new ConcurrentLinkedQueue<>(network.getNodes().values());
        Set<Id<Node>> nodesInBoundary = ConcurrentHashMap.newKeySet();
        Counter counter = new Counter("Checking whether node ", " / " + network.getNodes().size() + " is within boundary");
        int numberOfThreads = Resources.instance.getInt(Properties.NUMBER_OF_THREADS);
        Thread[] threads = new Thread[numberOfThreads];
        for (int i = 0 ; i < numberOfThreads ; i++) {
            NodeWorker worker = new NodeWorker(allNodes,nodesInBoundary,boundary,counter);
            threads[i] = new Thread(worker,"NodeProcessor-" + i);
            threads[i].start();
        }

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        log.info("Identified " + nodesInBoundary.size() + " nodes within boundary.");
        return Set.copyOf(nodesInBoundary);
    }

    private static class NodeWorker implements Runnable {

        private static final GeometryFactory gf = new GeometryFactory();
        private final ConcurrentLinkedQueue<Node> allNodes;
        private final Set<Id<Node>> nodesInBoundary;
        private final Geometry boundary;
        private final Counter counter;

        NodeWorker(ConcurrentLinkedQueue<Node> allNodes, Set<Id<Node>> nodesInBoundary, Geometry boundary, Counter counter) {
            this.allNodes = allNodes;
            this.nodesInBoundary = nodesInBoundary;
            this.boundary = boundary;
            this.counter = counter;
        }
        public void run() {
            while(true) {
                Node node = this.allNodes.poll();
                if(node == null) {
                    return;
                }
                this.counter.incCounter();
                Coord c = node.getCoord();
                if(boundary.contains(gf.createPoint(new Coordinate(c.getX(),c.getY())))) {
                    nodesInBoundary.add(node.getId());
                }
            }
        }
    }

}
