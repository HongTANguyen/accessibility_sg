package resources;

import decay.*;
import org.apache.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.router.costcalculators.FreespeedTravelTimeAndDisutility;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;
import util.Bicycle;
import disutility.DistanceDisutility;
import disutility.CompositeDisutility;
import travelTime.BicycleTravelTime;
import travelTime.WalkTravelTime;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class Resources {

    public static final Logger log = Logger.getLogger(Resources.class);

    public static Resources instance;
    private final Properties properties;
    private final Path baseDirectory;
    private Config config;
    private String mode;
    private Vehicle veh;
    private TravelTime tt;
    private TravelDisutility td;
    private Boolean fwd;
    private DecayFunction decayFunction;

    private Resources(Properties properties, String baseDirectory) {
        this.properties = properties;
        this.baseDirectory = Paths.get(baseDirectory).getParent();
    }

    public static void initializeResources(String propertiesFile) {
        try (FileInputStream in = new FileInputStream(propertiesFile)) {
            Properties properties = new Properties();
            properties.load(in);
            instance = new Resources(properties, propertiesFile);

            // Config
            instance.config = ConfigUtils.createConfig();

            // Get mode
            instance.mode = properties.getProperty(resources.Properties.MODE);

            // Vehicle, travelTime, and TravelDisutility
            switch (instance.mode) {
                case TransportMode.bike:
                    Bicycle bicycle = new Bicycle(instance.config);
                    instance.veh = bicycle.getVehicle();
                    instance.tt = bicycle.getTravelTime();
                    String stressThreshold = properties.getProperty(resources.Properties.CYCLE_STRESS_THRESHOLD);
                    if(stressThreshold != null) {
                        ((BicycleTravelTime) instance.tt).setLinkStressThreshold(stressThreshold);
                    }
                    instance.setActiveDisutility();
                    break;
                case TransportMode.walk:
                    instance.veh = null;
                    instance.tt = new WalkTravelTime();
                    instance.setActiveDisutility();
                    break;
                case TransportMode.car:
                    FreespeedTravelTimeAndDisutility freeSpeed = new FreespeedTravelTimeAndDisutility(instance.config.planCalcScore());
                    instance.veh = null;
                    instance.tt = freeSpeed;
                    instance.td = freeSpeed;
                    break;
                default:
                    throw new RuntimeException("Mode " + instance.mode + " not supported for accessibility calculations!");
            }

            // Decay function
            instance.setDecayFunction();

            // Direction
            String input = properties.getProperty(resources.Properties.FORWARD);
            if(input == null) {
                instance.fwd = null;
            } else if(input.equalsIgnoreCase("true")) {
                instance.fwd = true;
            } else if(input.equalsIgnoreCase("false")) {
                instance.fwd = false;
            } else {
                throw new RuntimeException("Unknown value " + input + " given for forward property. Must be \"true\", \"false\", or left out for a two-way analysis.");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setActiveDisutility() {
        String type = properties.getProperty(resources.Properties.IMPEDANCE);
        boolean dayOverride = false;
        switch(type) {
            case "shortest":
            case "short":
                td = new DistanceDisutility();
                break;
            case "fastest":
            case "fast":
                td = new OnlyTimeDependentTravelDisutility(tt);
                break;
            case "jibe_day":
                dayOverride = true;
            case "jibe_night":
                td = new CompositeDisutility(mode,tt,dayOverride);
                break;
            default:
                throw new RuntimeException("Disutility type " + type + " not recognised for mode " + mode);
        }
    }

    public void setDecayFunction() {

        // Decay function
        String decayType = properties.getProperty(resources.Properties.DECAY_FUNCTION);
        double cutoffTime = getDouble(resources.Properties.CUTOFF_TIME);
        double cutoffDist = getDouble(resources.Properties.CUTOFF_DISTANCE);

        if(decayType == null) {
            throw new RuntimeException("No decay function specified!");
        } else if (decayType.equalsIgnoreCase("exponential")) {
            double beta = instance.getDouble(resources.Properties.BETA);
            log.info("Initialising exponential decay function with the following parameters:" +
                    "\nBeta: " + beta +
                    "\nTime cutoff (seconds): " + cutoffTime +
                    "\nDistance cutoff (meters): " + cutoffDist);
            this.decayFunction = new Exponential(beta,cutoffTime,cutoffDist);
        } else if (decayType.equalsIgnoreCase("power")) {
            double a = instance.getDouble(resources.Properties.A);
            log.info("Initialising power decay function with the following parameters:" +
                    "\na: " + a +
                    "\nTime cutoff (seconds): " + cutoffTime +
                    "\nDistance cutoff (meters): " + cutoffDist);
            this.decayFunction = new Power(a,cutoffTime,cutoffDist);
        } else if (decayType.equalsIgnoreCase("cumulative")) {
            log.info("Initialising cumulative decay function with the following parameters:" +
                    "\nTime cutoff (seconds): " + cutoffTime +
                    "\nDistance cutoff (meters): " + cutoffDist);
            this.decayFunction = new Cumulative(cutoffTime, cutoffDist);
        } else if (decayType.equalsIgnoreCase("gaussian")) {
            double v = instance.getDouble(resources.Properties.V);
            log.info("Initialising gaussian decay function with the following parameters:" +
                    "\nv: " + v +
                    "\nTime cutoff (seconds): " + cutoffTime +
                    "\nDistance cutoff (meters): " + cutoffDist);
            this.decayFunction = new Gaussian(v,cutoffTime,cutoffDist);
        } else if (decayType.equalsIgnoreCase("cumulative gaussian")) {
            double a = instance.getDouble(resources.Properties.A);
            double v = instance.getDouble(resources.Properties.V);
            log.info("Initialising cumulative gaussian decay function with the following parameters:" +
                    "\na: " + a +
                    "\nv: " + v +
                    "\nTime cutoff (seconds): " + cutoffTime +
                    "\nDistance cutoff (meters): " + cutoffDist);
            this.decayFunction = new CumulativeGaussian(a,v,cutoffTime,cutoffDist);
        } else {
            throw new RuntimeException("Do not recognise decay function type \"" + decayType + "\"");
        }
    }

    public synchronized String getMode() {
        return this.mode;
    }

    public synchronized Boolean fwdCalculation() { return this.fwd; }

    public synchronized DecayFunction getDecayFunction() { return this.decayFunction; }

    public synchronized Vehicle getVehicle() {
        return this.veh;
    }

    public synchronized TravelTime getTravelTime() {
        return this.tt;
    }

    public synchronized TravelDisutility getTravelDisutility() {
        return this.td;
    }

    public synchronized String getString(String key) {
        return properties.getProperty(key);
    }

    public synchronized int getInt(String key) {
        return Integer.parseInt(properties.getProperty(key));
    }

    public synchronized double getDouble(String key) {
        String value = properties.getProperty(key);
        return value != null ? Double.parseDouble(value) : Double.NaN;
    }

    public synchronized List<String> getStringList(String key) {
        ArrayList<String> strings = new ArrayList<>();

        String onlyString = properties.getProperty(key);
        if(onlyString != null) {
            // Case 1: only single item (i.e., non-numbered)
            strings.add(onlyString);
        } else {
            // Case 2: multiple numbered items (starting from 0)
            int counter = 0;
            String nextString = properties.getProperty(key + "." + counter);
            while(nextString != null) {
                strings.add(nextString);
                counter++;
                nextString = properties.getProperty(key + "." + counter);
            }
        }
        return strings;
    }


}
