# Accessibility

This package contains tools and methods for calculating active mode accessibility using micro-scale network and land use data.
Accessibilities can be computed for multiple scales including:
- Fully disaggregate (e.g., specific dwellings)
- Zones (e.g., transport analysis zones or grid cells, which can be created using gis/CreateGrid.java)

The runnable main method is **_accessibility/RunAnalysis.java_**.

The speed and disutility algorithms are based partly on code from the "bicycle" MATSim extension which
can be found here: https://github.com/matsim-org/matsim-libs/tree/master/contribs/bicycle.
The extension is also described in this paper: https://doi.org/10.1016/j.procs.2017.05.424

The accessibility calculations require efficiently routing to a large number of destinations.
Our code for doing this is based largely on code by the swiss federal railway (SBB) which can be found here:
https://github.com/SchweizerischeBundesbahnen/matsim-sbb-extensions

The inputs, outputs, and specifications for each accessibility computation are described using properties files
(with suffix .properties). Multiple properties files can be passed in at once, and they will be called sequentially.

## Accessibility properties file

The properties file defines the inputs, outputs, and accessibility specification including the mode, impedance, decay function, and desired cutoff:

In this file, we specify:
- **region.boundary** Boundary of the study area
- **network.boundary** Boundary of the network, which must at least contain the study area. Optimally the network boundary should cover additional area to reduce edge effects.
- **matsim.network** Network file in MATSim .xml format
- **end.coords.[i]** End point (destination) coordinates. Can specify multiple, using [i] as an integer starting from 0. See following section for details.
- **end.description.[i]** Short end point (destination) description, without spaces, corresponding to each coordinate file (above). Used to differentiate destinations in attribute name when results are written.
- **end.alpha.[i]** Power transform for destination weights, corresponding to each coordinate file (above). For no power transform, set to 1.
- **input** Input file in .gpkg formt. Can either be points (for fully disaggregate analysis) or polygons (for zone-based analysis).
- **output** Output file in .gpkg format. The output will be an extended version of the input file, containing additional attributes with the accessibility results.
- (OPTIONAL) **output.nodes** Optional output of accessibility results at each network node
- **mode** walk or bike
- **disutility** desired impedance function. Can specify shortest, fastest, or composite (currently includes jibe_day and jibe_night).
- (OPTIONAL) **forward** Set to _true_ to compute impedance in the forward direction, _false_ for the reverse direction, or comment out to compute in both directions.
- **decay function** Cumulative, exponential, gaussian, power, or cumulative gaussian. Must also include corresponding parameters:
    - _cumulative_: specify either **cutoff.time** (seconds) or **cutoff.distance** (metres)
    - _exponential_: specify **beta** (decay parameter)
    - _gaussian_: specify **v** (variance parameter)
    - _power_: specify **a** (alpha parameter)
    - _cumulative gaussian_: specify **a** (acceptable impedance) and **v** (variance parameter)
    - **time** and/or **cutoff.distance** may be specified for all decay functions to reduce computational burden, but it is only required when a cumulative decay function is specified.
- **coordinate.system** coordinate system used for the transport network and destinations (these must use the same coordinate system)
- **number.of.threads** number of threads for multithreaded computation
- **max.bike.speed** maximum possible bicycle speed (see travelTime package)
  Further instructions on specifying accessibility properties are given in the accessibility properties file (resources/example.properties).

### End coords file
These are is a .csv file, seperated with a semicolon (;) specifying the destinations to be routed to and their weights.
They should have the following attributes:

- **ID:** a unique ID for each destination.
- **X:** the x-coordinate
- **Y:** the y-coordinate
- **WEIGHT:** the weight of each destination. If not provided, we assume all destinations have equal weight of 1.

This code also supports destinations with multiple access points (e.g. large green spaces).
If a destination has multiple access points, include each access point coordinates as a separate line in the destinations file, but keep their destination ID the same.
The code will calculate costs to all possible access points but the final accessibility calculation will only consider the access point with the lowest cost from the origin.

### grid/CreateGrid.java
Creates a geopackage of hexagonal grid cells within the region boundary
(specified by the property _region.boundary_ in the properties file).
The desired side length for each cell must be specified as an argument.