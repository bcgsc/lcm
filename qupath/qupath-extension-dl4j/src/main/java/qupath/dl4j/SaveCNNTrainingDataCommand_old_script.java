package qupath.dl4j;

import groovy.lang.Binding;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import groovy.lang.GroovyShell;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by cschlosser on 01/06/2017.
 */
public class SaveCNNTrainingDataCommand_old_script implements PathCommand {

    final private static Logger logger = LoggerFactory.getLogger(SaveCNNTrainingDataCommand_old_script.class);

    private QuPathGUI qupath;
    //private ImageData<BufferedImage> imageData;
    //private PathObjectHierarchy hierarchy;

    public SaveCNNTrainingDataCommand_old_script(QuPathGUI qupath){

        this.qupath = qupath;

    }

    @Override
    public void run() {


        InputStream in = DL4JExtension.class.getResourceAsStream("/cnn-tile.groovy");//new URL("src/main/resources/dl4j-icon.bmp");
        if(in != null) {
            BufferedReader exportTestDataScript = new BufferedReader(new InputStreamReader(in));
            Binding binding = new Binding();
            GroovyShell shell = new GroovyShell(binding);
            shell.evaluate(exportTestDataScript);
        }
        else{
            logger.error("Error tiling script");
        }

    }

}
