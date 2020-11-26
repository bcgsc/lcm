package qupath.dl4j;

/**
 * Created by cschlosser on 28/06/2018.
 */


import org.datavec.image.loader.Java2DNativeImageLoader;
import org.datavec.image.loader.NativeImageLoader;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.KerasModelImport;
import org.deeplearning4j.nn.modelimport.keras.exceptions.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.exceptions.UnsupportedKerasConfigurationException;
import org.deeplearning4j.parallelism.ParallelInference;
import org.deeplearning4j.parallelism.inference.InferenceMode;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.impl.broadcast.BroadcastAddOp;
import org.nd4j.linalg.api.ops.impl.broadcast.BroadcastSubOp;
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization;
import org.nd4j.linalg.dataset.api.preprocessor.ImagePreProcessingScaler;
import org.nd4j.linalg.dataset.api.preprocessor.VGG16ImagePreProcessor;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.versioncheck.VersionCheck;
import org.nd4j.versioncheck.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.algorithms.TilerPlugin;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.scriptable.DuplicateAnnotationCommand;
import qupath.lib.gui.helpers.MeasurementMapper;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathTileObject;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.CommandLinePluginRunner;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.*;
import qupath.lib.roi.interfaces.PathShape;

import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;


import static qupath.lib.roi.PathROIToolsAwt.getArea;


/**
 * Created by cschlosser on 01/06/2018.
 */
public class ClassifyTilesInteractivePlugin extends AbstractInteractivePlugin<BufferedImage> {

    final private static Logger logger = LoggerFactory.getLogger(ClassifyTilesInteractivePlugin.class);

    private ParameterList params;
    private int tileSize;

    private String lastMessage = null;
    private ParallelInference pi;
    ComputationGraph restoredCNN;

    private MeasurementMapper.ColorMapper colorMapper = new MeasurementMapper.PseudoColorMapper();
    //ToDo: Likewise the scaling and preprocessing shouldn't be fixed.
    List<String> preProcessingStyles = new ArrayList<>();
    final String imageNetMeanSub = "ImageNet Mean Subtraction";
    final String zeroCenterScaled = "( -1 to 1 ) Scaling";


    public ClassifyTilesInteractivePlugin() {


        preProcessingStyles.add(imageNetMeanSub);
        preProcessingStyles.add(zeroCenterScaled);

        params = new ParameterList();
        this.params.addIntParameter("tileSize", "Select tile size in pixels?", 256, "Px", 10, 2500);
        this.params.addChoiceParameter("preProcessingStyle","Preprocessing Method: ",preProcessingStyles.get(0),preProcessingStyles);
        this.params.addBooleanParameter("tileOverlap", "Overlap half-tile width?", false, "Selecting yes will create tiles every 1/2 tile size instead of adjacent");

    }


    public boolean requestLiveUpdate() {
        return false;
    }


    @Override
    public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
        List<Class<? extends PathObject>> list = new ArrayList<>(1);
        list.add(PathAnnotationObject.class);
        return list;
    }


    @Override
    protected void addRunnableTasks(final ImageData<BufferedImage> imageData, final PathObject parentObject, List<Runnable> tasks) {

        Runnable runnable = new Runnable() {

            @Override
            public void run() {


                DataNormalization scaler = new ImagePreProcessingScaler(-1, 1);
                final INDArray VGG_MEAN_OFFSET_BGR = Nd4j.create(new double[] {103.939, 116.779, 123.68 });
                //final INDArray VGG_MEAN_OFFSET_BGR = Nd4j.create(new double[] {123.68, 116.779, 103.939 });

                parentObject.setColorRGB(ColorTools.makeRGB(255, 255, 0));
                //imageData.getHierarchy().fireObjectsChangedEvent(this, Collections.singleton(parentObject), false);

                ImageServer<BufferedImage> server = imageData.getServer();
                RegionRequest request = RegionRequest.createInstance(server.getPath(), 1, parentObject.getROI());

                //Get image object
                BufferedImage img = server.readRegion(request).getImage();
                // We Convert Buffered Image Type becasue we want an Image that Java2dNativeImageLoader can read with flip channels true/false depending on the preprocessing
                // This is because the vgg16ImagePreprocessor doesn't flip the image the same way as the keras one does, meaning we have to do things manually ourselves.
                BufferedImage imgBGR = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
                imgBGR.getGraphics().drawImage(img, 0, 0, null);


                String preProcessingStyle = (String)params.getChoiceParameterValue("preProcessingStyle");

                try {

                    Java2DNativeImageLoader loader = new Java2DNativeImageLoader(tileSize, tileSize, 3);
                    INDArray imageArray;

                    switch(preProcessingStyle){
                        case imageNetMeanSub:

                            imageArray = loader.asMatrix(imgBGR,false);
                            Nd4j.getExecutioner().execAndReturn(new BroadcastSubOp(imageArray.dup(), VGG_MEAN_OFFSET_BGR, imageArray, 1));

                              //For reasons unclear to me at the moment this preprocessor doesn't flip the channels like the standard ImageNet preprocessor in Keras does
//                            vgg16PreProcessor.preProcess(imageArray);
                              //So we do it manually
//                            INDArray tempImageArray = imageArray.dup();
//                            imageArray.get( NDArrayIndex.all(), NDArrayIndex.point(0), NDArrayIndex.all(), NDArrayIndex.all() ).assign( tempImageArray.get( NDArrayIndex.all(), NDArrayIndex.point(2), NDArrayIndex.all(), NDArrayIndex.all() ) );
//                            imageArray.get( NDArrayIndex.all(), NDArrayIndex.point(2), NDArrayIndex.all(), NDArrayIndex.all() ).assign( tempImageArray.get( NDArrayIndex.all(), NDArrayIndex.point(0), NDArrayIndex.all(), NDArrayIndex.all() ) );

                            break;
                        case zeroCenterScaled:
                            imageArray = loader.asMatrix(imgBGR,true);
                            scaler.transform(imageArray);
                            break;
                        default:
                            logger.error("Invalid Preprocessing Selection");
                            return;

                    }

                    double outputProb = pi.output(imageArray).getDouble(0);

                    // Add new measurement to the measurement list of the detection
                    //Todo: link this value to a name to add to the measurement
                    parentObject.getMeasurementList().addMeasurement("Tumour Probablity", outputProb);

                    // It's important for efficiency reasons to close the list
                    parentObject.getMeasurementList().closeList();

                    parentObject.setColorRGB(colorMapper.getColor(outputProb, 0.0, 1.0));
                    //imageData.getHierarchy().fireObjectClassificationsChangedEvent(this, Collections.singleton(parentObject));
                    //imageData.getHierarchy().fireObjectsChangedEvent(null, Collections.singleton(parentObject), true);

                } catch (IOException ex) {
                    lastMessage = "Error getting image from QuPath to classify";
                }

            }
        };

        tasks.add(runnable);


    }


    @Override
    public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
        return params;
    }


    @Override
    public String getName() {
        return "Export LMD Map";
    }

    @Override
    public String getLastResultsDescription() {
        return null;
    }


    @Override
    public String getDescription() {
        return "Export Cell or Annotation Boundaries for Laser Capture on Leica LMD7000";
    }


    @Override
    protected Collection<? extends PathObject> getParentObjects(final PluginRunner<BufferedImage> runner) {
        Collection<Class<? extends PathObject>> supported = getSupportedParentObjectClasses();
        Collection<PathObject> selectedObjects = runner
                .getHierarchy()
                .getSelectionModel()
                .getSelectedObjects();

        ArrayList<PathObject> subTiles = new ArrayList<>();

        Collection<? extends PathObject> objects = PathObjectTools.getSupportedObjects(selectedObjects, supported);

        for(PathObject parentObject : objects){

            ArrayList<PathObject> tempChildren = new ArrayList<>();
            runner.getHierarchy().getDescendantObjects(parentObject,tempChildren, PathTileObject.class);
            subTiles.addAll(tempChildren);

        }


        // In the event that not all selected objects were chosen, we need to deselect the others
        if (!objects.isEmpty() && selectedObjects.size() > objects.size()) {
            Set<PathObject> objectsToDeselect = new HashSet<>(selectedObjects);
            objectsToDeselect.removeAll(objects);
            runner.getHierarchy().getSelectionModel().deselectObjects(objectsToDeselect);
        }


        //Return tile objects - each one corresponds to a task
        return subTiles;
    }


    @Override
    protected void addWorkflowStep(final ImageData<BufferedImage> imageData, final String arg) {
    }

    @Override
    protected void preprocess(final PluginRunner<BufferedImage> pluginRunner) {



//        VersionCheck.checkVersions();
//        List<VersionInfo> dependencies = VersionCheck.getVersionInfos();
//        dependencies.get(0).getRemoteOriginUrl();
//        VersionCheck.logVersionInfo();

        tileSize = params.getIntParameterValue("tileSize");
        boolean tileOverlap = params.getBooleanParameterValue("tileOverlap");

        File directory = new File("");
        File modelFile = QuPathGUI.getSharedDialogHelper().promptForFile("Select CNN Model", directory, null, null);
        if (modelFile == null) {
            logger.error("No model selected.");
            return;
        }

//        String modelPathResnet = "H:\\resnet-120618-sgdm.h5";
//        File modelFile = new File(modelPathResnet);

        PathObjectHierarchy hierarchy = pluginRunner.getHierarchy();
        ArrayList<PathObject> selectedObjects = new ArrayList<>(hierarchy.getSelectionModel().getSelectedObjects());

        ArrayList<PathObject> duplicatePathObjects = new ArrayList<>();
        for(PathObject originalObject : selectedObjects)
        {
            PathObject pathObjectNew = new PathAnnotationObject(originalObject.getROI().duplicate(), originalObject.getPathClass());
            hierarchy.addPathObject(pathObjectNew, false);
            duplicatePathObjects.add(pathObjectNew);
        }

        hierarchy.getSelectionModel().clearSelection();
        hierarchy.getSelectionModel().selectObjects(duplicatePathObjects);


        //Run Tiler Plugin
        //CNNTilerPlugin tiler = new TilerPlugin();
        TilerPlugin tiler = new TilerPlugin();
        CommandLinePluginRunner runner = new CommandLinePluginRunner(pluginRunner.getImageData(),false);
        String tilerArgs = String.format("{\"tileSizeMicrons\": %f ,  \"trimToROI\": false,  \"makeAnnotations\": false,  \"removeParentAnnotation\": false}", (double) tileSize * pluginRunner.getImageData().getServer().getPixelWidthMicrons());
        //ToDo: Tile size should either be user selected or (ideally) based on the loaded model's size.
        //ToDo: At the very least there should be a check whether the tile size actually fits the model...
        //ToDo: The tiler kills any previous children of the selected annotations, need to find a good way of warning or preserving them
        tiler.runPlugin(runner, tilerArgs);


        PathObjectTools.filterROIs(duplicatePathObjects, PathShape.class);

        if (tileOverlap) {

            for (PathObject boundingObject : duplicatePathObjects) {

                Collection<PathObject> overlapTiles = new ArrayList<>();
                Collection<PathObject> childrenTiles = boundingObject.getChildObjects();

                for (PathObject tile : childrenTiles) {

                    if (tile.isTile()) {
                        RectangleROI tileROI = (RectangleROI) tile.getROI();
                        double tileX = tileROI.getBoundsX();
                        double tileY = tileROI.getBoundsY();
                        double tileWidth = tileROI.getBoundsWidth();
                        double tileHeight = tileROI.getBoundsHeight();

                        Area area = getArea(boundingObject.getROI());
                        if (area.contains(tileX + tileSize / 2 + tileWidth / 2, tileY + tileHeight / 2)) {
                            PathTileObject tempTile = new PathTileObject(new RectangleROI(tileX + tileSize / 2, tileY, tileWidth, tileHeight));
                            tempTile.setName(tile.getName() + " - Stride X");
                            overlapTiles.add(tempTile);
                        }
                        if (area.contains(tileX + tileWidth / 2, tileY + tileSize / 2 + tileHeight / 2)) {
                            PathTileObject tempTile = new PathTileObject(new RectangleROI(tileX, tileY + tileSize / 2, tileWidth, tileHeight));
                            tempTile.setName(tile.getName() + " - Stride Y");
                            overlapTiles.add(tempTile);
                        }
                        if (area.contains(tileX + tileWidth / 2 + tileWidth / 2, tileY + tileSize / 2 + tileHeight / 2)) {
                            PathTileObject tempTile = new PathTileObject(new RectangleROI(tileX + tileSize / 2, tileY + tileSize / 2, tileWidth, tileHeight));
                            tempTile.setName(tile.getName() + " - Stride XY");
                            overlapTiles.add(tempTile);
                        }

                    }

                }

                if (overlapTiles.size() > 0) {
                    boundingObject.addPathObjects(overlapTiles);
                    pluginRunner.getHierarchy().fireHierarchyChangedEvent(this, boundingObject);
                }
            }


        }

        //must select again here so that the correct tiles can be grabbed in getParentObjects below
        hierarchy.getSelectionModel().selectObjects(duplicatePathObjects);

        Collection<?extends PathObject> tileObjects =  getParentObjects(pluginRunner);
        for(PathObject tile : tileObjects){

            tile.setColorRGB(ColorTools.makeRGB(128,128,128));
        }

        try {
            restoredCNN =  KerasModelImport.importKerasModelAndWeights( modelFile.getAbsolutePath(),new int[]{tileSize, tileSize, 3},false);
        }catch(InvalidKerasConfigurationException ex){
            lastMessage = "Could not load CNN model: "+ex.getMessage()+"  Cause:  "+ ex.getCause();
            return;
        }catch(UnsupportedKerasConfigurationException ex){
            lastMessage = "Could not load CNN model: "+ex.getMessage();
            return;
        } catch(IOException ex){
            lastMessage = "Could not load CNN model: IO Exception";
            return;
        }


        if(restoredCNN == null)
        {
            lastMessage = "CNN model is not valid";
            return;
        }

//        logger.info("Successfully loaded CNN");
//        logger.info("Final Parameter:" + Double.toString(restoredCNN.params().getDouble( restoredCNN.params().length() -1 ) ) );



//         Double finalParm = restoredCNN.params().getDouble( restoredCNN.params().length() -1 );

        pi = new ParallelInference.Builder(restoredCNN)
                .inferenceMode(InferenceMode.BATCHED)
                .batchLimit(32)
                .workers(1)
                .build();


        //ToDo: Figure out why the model wieghts don't get loaded correctly here.
        // There seems to be some kind of timing/threading issue here. If no access to the CNN parameters is made before the end of preprocess(),
        // model weights don't seem to get loaded correctly (some of the weights don't get set).
        // Needs deeper investigation - this is extremely hokey fix.
        //Somehow the weight setting is being interrupted by other threads maybe?
        Double finalParm = restoredCNN.params().getDouble( restoredCNN.params().length() -1 );


    }


    @Override
    protected void postprocess(final PluginRunner<BufferedImage> pluginRunner) {

        pluginRunner.getHierarchy().fireHierarchyChangedEvent(this);

    }


}