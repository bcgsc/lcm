package qupath.LMDExport;


import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.classifiers.PathClassificationLabellingHelper;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.PluginRunnerFX;
import qupath.lib.plugins.objects.SmoothFeaturesPlugin;

import java.util.*;
import java.util.List;
import java.util.function.Predicate;

/**
 * Created by cschlosser on 01/06/2017.
 * This command creates and opens the DrawContourMapPanel which is used to visualize and draw contours.
 * Before opening the panel it uses the smoothing plugin to smooth the current available measurements.
 */
public class DrawContourMap implements PathCommand {


    private QuPathGUI qupath;
    private Stage dialog = null;
    private DrawContourMapPanel panel;
    final private static Logger logger = LoggerFactory.getLogger(DrawContourMap.class);


    public DrawContourMap(QuPathGUI qupath){

        this.qupath = qupath;

    }

    public void run(){

        //Check for loaded image
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null || viewer.getServer() == null)
        {
            logger.error("No Slide Loaded.");
            return;
        }

        //Get all of the objects and the bounding annotation
        PathObjectHierarchy hierarchy = viewer.getHierarchy();
        ImageServer server =  viewer.getServer();
        List<PathObject> pathObjects = hierarchy.getObjects(null, PathDetectionObject.class);

        List<PathObject> validBoundingPathObjects = new ArrayList<>(viewer.getAllSelectedObjects());
        Predicate<PathObject> invalidObject = p -> !p.isAnnotation() || p.getROI() == null;
        validBoundingPathObjects.removeIf(invalidObject);

        if (validBoundingPathObjects.isEmpty() )
        {
            logger.error("No valid annotations from which to export contour.");
            return;
        }


        //Class isn't stored as measurement so we add them here. We have to subset based on available classes which first involves determining the classes;
        //Get unique classes that have objects associated with them
        Set<PathClass> pathClasses = PathClassificationLabellingHelper.getRepresentedPathClasses(qupath.getImageData().getHierarchy(), PathDetectionObject.class);

        for(PathObject pathObject : pathObjects)
        {
            if(pathObject.getPathClass() != null && !pathObject.getMeasurementList().containsNamedMeasurement( pathObject.getPathClass().getName() ) )
            {
                PathClass pathObjectPathClass = pathObject.getPathClass();
                for(PathClass pathClass : pathClasses)
                {
                    double matchClass = pathClass.compareTo(pathObjectPathClass) == 0 ? 1.0 : 0.0;
                    pathObject.getMeasurementList().addMeasurement(pathClass.getName(), matchClass );
                }
            }

        }

        //Run smoothing so that occasional incorrectly mislabeled cells and gaps between cells will be spatially filtered.
        PluginRunnerFX runner = new PluginRunnerFX(qupath,false);
        SmoothFeaturesPlugin smoother = new SmoothFeaturesPlugin();
        smoother.runPlugin(runner, "{\"fwhmMicrons\": 25.0 ,  \"smoothWithinClasses\": false,  \"useLegacyNames\": false}");
        smoother.runPlugin(runner, "{\"fwhmMicrons\": 50.0 ,  \"smoothWithinClasses\": false,  \"useLegacyNames\": false}");
        List<String> measurementList = new ArrayList<>(PathClassificationLabellingHelper.getAvailableFeatures(pathObjects));

        if (measurementList.size() == 0)
        {
            logger.error("No measurements exist from which create contour.");
            return;
        }

        //Open the panel
        dialog = new Stage();
        dialog.initOwner(qupath.getStage());
        dialog.setTitle("Draw Contour");

        panel = new DrawContourMapPanel(qupath);
        panel.setPathObjects(pathObjects,validBoundingPathObjects);

        BorderPane pane = new BorderPane();
        pane.setCenter(panel.getPane());

        Scene scene = new Scene(pane, 300, 400);
        dialog.setScene(scene);
        dialog.setMinWidth(400);
        dialog.setMinHeight(550);

        dialog.setOnCloseRequest(e -> {
            OverlayOptions overlayOptions = qupath.getViewer().getOverlayOptions();
            if (overlayOptions != null)
                overlayOptions.resetMeasurementMapper();
            dialog.hide();
        });

        dialog.show();

    }


}