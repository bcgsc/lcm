package qupath.dl4j;

import org.datavec.image.loader.Java2DNativeImageLoader;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.deeplearning4j.parallelism.ParallelInference;
import org.deeplearning4j.parallelism.inference.InferenceMode;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.broadcast.BroadcastSubOp;
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization;
import org.nd4j.linalg.dataset.api.preprocessor.ImagePreProcessingScaler;
import org.nd4j.linalg.dataset.api.preprocessor.VGG16ImagePreProcessor;
import org.nd4j.linalg.factory.Nd4j;
import qupath.imagej.images.servers.ImagePlusServer;
import qupath.imagej.images.servers.ImagePlusServerBuilder;
import qupath.lib.algorithms.TilerPlugin;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;

import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import qupath.lib.gui.helpers.DisplayHelpers;
import qupath.lib.gui.helpers.MeasurementMapper;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathTileObject;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.CommandLinePluginRunner;
import qupath.lib.plugins.ParameterDialogWrapper;
import qupath.lib.plugins.PluginRunnerFX;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.PathShape;

import static qupath.lib.roi.PathROIToolsAwt.getArea;

//import qupath.lib.roi.PathROIToolsAwt.getArea;


/**
 * Created by cschlosser on 01/06/2017.
 * This command runs the ClassifyTilesCNN plugin
 */

public class ClassifyTilesCNNCommand implements PathCommand {

    final private static Logger logger = LoggerFactory.getLogger(ClassifyTilesCNNCommand.class);
    private MeasurementMapper.ColorMapper colorMapper = new MeasurementMapper.PseudoColorMapper();


    public static ParallelInference pi;
    private QuPathGUI qupath;
    //private ImageData<BufferedImage> imageData;
    //private PathObjectHierarchy hierarchy;

    public ClassifyTilesCNNCommand(QuPathGUI qupath){

        this.qupath = qupath;

    }

    public void run(){



        //It's nicer to visualize the classification with semi-opaque overlay so we will switch it here:
        OverlayOptions overlayOptions = qupath.getViewer().getOverlayOptions();
        overlayOptions.setOpacity(0.5f);
        overlayOptions.setFillObjects(true);
        overlayOptions.setShowObjects(true);
        overlayOptions.setShowAnnotations(true);

        PluginRunnerFX runner = new PluginRunnerFX(qupath,false);

        ClassifyTilesInteractivePlugin classifyTilesInteractivePlugin = new ClassifyTilesInteractivePlugin();
        ParameterDialogWrapper<BufferedImage> dialog = new ParameterDialogWrapper<>(classifyTilesInteractivePlugin, classifyTilesInteractivePlugin.getDefaultParameterList(qupath.getImageData()), runner);
        dialog.showDialog();

//
//        //Set up Parameters
//        List<String> preProcessingStyles = new ArrayList<>();
//
//        final String imageNetMeanSub = "ImageNet Mean Subtraction";
//        final String zeroCenterScaled = "( -1 to 1 ) Scaling";
//
//        preProcessingStyles.add(imageNetMeanSub);
//        preProcessingStyles.add(zeroCenterScaled);
//
//        ParameterList params = new ParameterList();
//        params.addIntParameter("tileSize", "Select tile size in pixels?",224,"Px",10,2500);
//        params.addChoiceParameter("preProcessingStyle","Preprocessing Method: ",preProcessingStyles.get(1),preProcessingStyles);
//        params.addBooleanParameter("tileOverlap", "Overlap half-tile width?",false,"Selecting yes will create tiles every 1/2 tile size instead of adjacent");
//
//        //Display paramater list
//        boolean inputSuccess = DisplayHelpers.showParameterDialog("Classify Tiles",params);
//
//        //Parse inputs
//        int tileSize = params.getIntParameterValue("tileSize");
//        boolean tileOverlap = params.getBooleanParameterValue("tileOverlap");
//        String preProcessingStyle = (String)params.getChoiceParameterValue("preProcessingStyle");
//
//        ImageData currentImageData = qupath.getViewer().getImageData();
//        if(currentImageData == null)
//        {
//            logger.error("Load slide and set up annotations before running script!");
//            return;
//        }
//
//        QuPathViewer viewer = qupath.getViewer();
//        if (viewer == null || viewer.getServer() == null)
//        {
//            logger.error("No Slide Loaded.");
//            return;
//        }
//
//        ImageServer<BufferedImage> serverOriginal = currentImageData.getServer();
//        ImagePlusServer server = ImagePlusServerBuilder.ensureImagePlusWholeSlideServer(serverOriginal);
//
//
//        File directory = new File("");
//        File modelFile = QuPathGUI.getSharedDialogHelper().promptForFile("Select CNN Model", directory, null, null);
//        if (modelFile == null) {
//            logger.error("No model selected.");
//            return;
//        }
//
//        ComputationGraph restoredCNN;
//
//        try {
//            restoredCNN =  KerasModelImport.importKerasModelAndWeights( modelFile.getAbsolutePath(),new int[]{tileSize, tileSize, 3},false);
//        }catch(InvalidKerasConfigurationException ex){
//            logger.error("Could not load CNN model: "+ex.getMessage()+"  Cause:  "+ ex.getCause());
//            return;
//        }catch(UnsupportedKerasConfigurationException ex){
//            logger.error("Could not load CNN model: "+ex.getMessage());
//            return;
//        } catch(IOException ex){
//            logger.error("Could not load CNN model: IO Exception");
//            return;
//        }
//
//        logger.info(Double.toString(restoredCNN.params().getDouble( restoredCNN.params().length() -1 ) ) );
//
//        pi = new ParallelInference.Builder(restoredCNN)
//                    .inferenceMode(InferenceMode.BATCHED)
//                    .batchLimit(32)
//                    .workers(1)
//                    .build();
//
//
//
//            List<PathObject> validSelectedPathObjects = new ArrayList<>(viewer.getAllSelectedObjects());
//            Predicate<PathObject> invalidObject = p -> !p.isAnnotation() || p.getROI() == null;
//            validSelectedPathObjects.removeIf(invalidObject);
//
//            if (validSelectedPathObjects.isEmpty() )
//            {
//                logger.error("No valid annotations selected to export.");
//                return;
//            }
//
//
//        //It's nicer to visualize the classification with semi-opaque overlay so we will switch it here:
//        OverlayOptions overlayOptions = qupath.getViewer().getOverlayOptions();
//        overlayOptions.setOpacity(0.5f);
//        overlayOptions.setFillObjects(true);
//        overlayOptions.setShowObjects(true);
//        overlayOptions.setShowAnnotations(true);
//
//        //Run the plugin to get progress visualizaiton
//        PluginRunnerFX runner = new PluginRunnerFX(qupath,false);
//
//        ClassifyTilesPlugin classifyTilesPlugin = new ClassifyTilesPlugin(tileSize,tileOverlap,preProcessingStyle);
//        new Thread(() -> classifyTilesPlugin.runPlugin(runner, null)).start();


    }


}