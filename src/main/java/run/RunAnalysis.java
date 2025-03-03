package run;

import calc.FeatureCalculator;
import calc.NodeCalculator;
import data.FeatureData;
import data.LocationData;
import decay.*;
import util.AccessibilityUtils;
import org.geotools.geometry.jts.Geometries;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;
import resources.Resources;
import resources.Properties;

import org.apache.log4j.Logger;
import util.AccessibilityWriter;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class RunAnalysis {

    public static final Logger log = Logger.getLogger(RunAnalysis.class);

    public static void main(String[] args) throws IOException {
        if(args.length < 1) {
            throw new RuntimeException("Program requires properties files to be passed in as arguments (at least 1)");
        }

        // Loop through each analysis and calculate
        for(int i = 1 ; i < args.length ; i++) {
            runAnalysis(args[i]);
        }
    }

    private static void runAnalysis(String propertiesFilepath) throws IOException {

        // Initialise properties file
        Resources.initializeResources(propertiesFilepath);

        // Region boundary
        log.info("Reading boundary shapefiles...");
        Geometry regionBoundary = AccessibilityUtils.readBoundary(Resources.instance.getString(Properties.REGION_BOUNDARY));;
        Geometry networkBoundary = AccessibilityUtils.readBoundary(Resources.instance.getString(Properties.NETWORK_BOUNDARY));

        // Check that origin boundary is within destination boundary
        if(!regionBoundary.within(networkBoundary)) {
            log.error("Region boundary must be within network boundary! Skipping accessibility calculations..."); // todo: avoid runtime exeptions!
            return;
        }

        // Mode
        String mode = Resources.instance.getMode();

        // Create mode-specific network
        Network network = AccessibilityUtils.readModeSpecificNetwork(mode);

        // Travel time, vehicle, disutility
        TravelTime tt = Resources.instance.getTravelTime();
        Vehicle veh = Resources.instance.getVehicle();
        TravelDisutility td = Resources.instance.getTravelDisutility();

        // Inputs/outputs
        String inputFilename = Resources.instance.getString(Properties.INPUT);

        List<String> endLocationsFilenames = Resources.instance.getStringList(Properties.END_LOCATIONS);
        List<String> endLocationsDescriptions = Resources.instance.getStringList(Properties.END_DESCRIPTION);
        List<Double> endLocationsAlpha = Resources.instance.getStringList(Properties.END_ALPHA).stream().map(Double::parseDouble).collect(Collectors.toList());

        String outputNodesFilename = Resources.instance.getString(Properties.OUTPUT_NODES);
        String outputFeaturesFilename = Resources.instance.getString(Properties.OUTPUT_FEATURES);

        // Parameters
        DecayFunction df = Resources.instance.getDecayFunction();
        Boolean fwd = Resources.instance.fwdCalculation();

        // Input locations (to calculate accessibility for)
        FeatureData features = new FeatureData(inputFilename, endLocationsDescriptions);

        // Checks on whether to perform ANY calculations
        if(df == null) {
            log.error("No decay function. Skipping all accessibility calculations.");
            return;
        }
        if(endLocationsFilenames.size() == 0) {
            log.error("No end locations given. Skipping all accessibility calculations.");
            return;
        }
        int endLocationsSize = endLocationsFilenames.size();
        if(endLocationsSize != endLocationsDescriptions.size()) {
            log.error("Number of end locations does not match number of end descriptions.");
        }
        if (outputNodesFilename == null && (inputFilename == null || outputFeaturesFilename == null)) {
            log.error("No input/output files given. Skipping all accessibility calculations.");
            return;
        }

        List<LocationData> endDataList = new ArrayList<>(endLocationsSize);
        for(int i = 0 ; i < endLocationsSize ; i++) {
            LocationData endData = new LocationData(endLocationsDescriptions.get(i),endLocationsFilenames.get(i),networkBoundary);
            endData.estimateNetworkNodes(network);
            endData.transformWeights(endLocationsAlpha.get(i));
            endDataList.add(endData);
        }

        // Accessibility calculation on NODES (if using polygons)
        Map<Id<Node>,double[]> nodeResults = null;
        if(Geometries.POLYGON.equals(features.getGeometryType())
                || Geometries.MULTIPOLYGON.equals(features.getGeometryType())) {

            // Get applicable start nodes
            log.info("Identifying origin nodes within area of analysis...");
            Set<Id<Node>> startNodes = AccessibilityUtils.getNodesInBoundary(network,regionBoundary);

            // Run node accessibility calculation
            log.info("Running node accessibility calculation...");
            long startTime = System.currentTimeMillis();
            NodeCalculator calc = new NodeCalculator(network,tt, td, veh, df);
            nodeResults = calc.calculate(startNodes, endDataList, fwd);
            long endTime = System.currentTimeMillis();
            log.info("Calculation time: " + (endTime - startTime));

            // Output nodes as CSV (if it was provided in properties file)
            if(outputNodesFilename != null) {
                AccessibilityWriter.writeNodesAsGpkg(nodeResults,endLocationsDescriptions,network,outputNodesFilename);
            }
        }

        if(inputFilename != null && outputFeaturesFilename != null) {

            log.info("Running accessibility calculation...");
            FeatureCalculator.calculate(network, features.getCollection(), endDataList,
                    nodeResults, features.getRadius(), fwd, tt, td, veh, df);

            // Output grid as gpkg
            log.info("Saving output features to " + outputFeaturesFilename);
            AccessibilityUtils.writeFeaturesToGpkg(features.getCollection(), features.getDescription() + "_result", outputFeaturesFilename);
        }
    }
}