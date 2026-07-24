package de.cwelsch.kinematiccorrelationapp.services.importservice.parser;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Universal contract for all motion capture system data parsers.
 * Standardizes how kinematic file tracks are digested into structured matrix segments.
 */
public interface Parser {

    /**
     * Component wrapper encapsulating parsed time-series coordinates and system parameters.
     */
    interface MotionData {

        /**
         * Retrieves the metadata properties extracted from the file header.
         * * @return A map containing configuration keys and their associated type-safe values.
         */
        Map<String, Object> getMetaData();

        /**
         * Retrieves the raw synchronized time-series data tracks.
         * * @return A map where keys represent column headers (e.g., "time", joint names)
         * and values contain the sequential frame data points.
         */
        Map<String, List<Double>> getData();
    }

    /**
     * Parses a kinematic input file from the filesystem into a unified data wrapper.
     * * @param filePath The absolute or relative system path pointing to the target data asset.
     * @return A fully populated MotionData instance matching the system's structural footprint.
     * @throws IOException If file streaming, access permissions, or structural parsing fails.
     */
    MotionData parse(String filePath) throws IOException;
}