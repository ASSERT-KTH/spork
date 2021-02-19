package se.kth.spork.cli;

import java.io.IOException;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/**
 * Class for providing the CLI with the current version
 *
 * @author Simon Larsén
 */
public class SporkVersionProvider implements IVersionProvider {
    @Override
    public String[] getVersion() throws Exception {
        return new String[] {getVersionFromPomProperties()};
    }

    // Adapted from https://stackoverflow.com/a/13632468
    private String getVersionFromPomProperties() {
        Properties props = new Properties();
        try {
            props.load(
                    getClass()
                            .getClassLoader()
                            .getResourceAsStream("META-INF/maven/se.kth/spork/pom.properties"));
            return props.getProperty("version");
        } catch (IOException e) {
            return "LOCAL";
        }
    }
}
