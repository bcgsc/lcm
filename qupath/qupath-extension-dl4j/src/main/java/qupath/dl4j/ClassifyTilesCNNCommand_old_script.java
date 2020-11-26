package qupath.dl4j;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by cschlosser on 01/06/2017.
 */
public class ClassifyTilesCNNCommand_old_script implements PathCommand {

    final private static Logger logger = LoggerFactory.getLogger(ClassifyTilesCNNCommand_old_script.class);


    private QuPathGUI qupath;
    //private ImageData<BufferedImage> imageData;
    //private PathObjectHierarchy hierarchy;

    public ClassifyTilesCNNCommand_old_script(QuPathGUI qupath){

        this.qupath = qupath;

    }


    public void run(){


        InputStream in = DL4JExtension.class.getResourceAsStream("/cnn-predict.groovy");

        if(in != null) {
                BufferedReader cnnPredict = new BufferedReader(new InputStreamReader(in));
                Binding binding = new Binding();
                GroovyShell shell = new GroovyShell(this.getClass().getClassLoader(), binding);
                shell.evaluate(cnnPredict);
            }
        else{
            logger.error("Error running cnn-predict script");
        }

    }


}