package qupath.LMDExport;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.plugins.ParameterDialogWrapper;
import qupath.lib.plugins.PluginRunnerFX;


import java.awt.image.BufferedImage;


/**
 * Created by cschlosser on 01/06/2017.
 * This command simply runs the associated Plugin. It isn't inherently a task that need to be parrllelized but since it takes some time, benefits from informing the user that it is running.
 */
public class ExportLMDMap implements PathCommand {


    private QuPathGUI qupath;
    final private static Logger logger = LoggerFactory.getLogger(DrawContourMap.class);


    public ExportLMDMap(QuPathGUI qupath){

        this.qupath = qupath;

    }

    public void run(){

        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null || viewer.getServer() == null)
        {
            logger.error("No Slide Loaded.");
            return;
        }

        PluginRunnerFX runner = new PluginRunnerFX(qupath,false);

        ExportLMDMapPlugin exportLMDMapPlugin = new ExportLMDMapPlugin();
        ParameterDialogWrapper<BufferedImage> dialog = new ParameterDialogWrapper<>(exportLMDMapPlugin, exportLMDMapPlugin.getDefaultParameterList(qupath.getImageData()), runner);
        dialog.showDialog();


    }


}