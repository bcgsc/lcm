package qupath.dl4j;



import org.datavec.image.loader.Java2DNativeImageLoader;
import org.datavec.image.loader.NativeImageLoader;
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
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.algorithms.TilerPlugin;
import qupath.lib.common.ColorTools;
import qupath.lib.gui.helpers.MeasurementMapper;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.*;
import qupath.lib.objects.helpers.PathObjectTools;

import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.AbstractPlugin;
import qupath.lib.plugins.CommandLinePluginRunner;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.RectangleROI;
import qupath.lib.roi.interfaces.PathShape;

import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static qupath.lib.roi.PathROIToolsAwt.getArea;

/**
 * Created by cschlosser on 24/04/2018.
 * This plugin tiles a selected annotation, prompts the user to load a CNN (saved from the Python Library Keras), and then used the CNN to classify the tiles.
 * Still under active development - exists almost as a test for DL4J Import
 *
 * Creates on task per tile, and uses a ParallelInference object to run them
 *
 */

//Todo: Allow loading DL4J Saved models. It might be better to have models be imported in a seperate command.
//Todo: Checks on tile size and preprocessing choice

public class ClassifyTilesPlugin extends AbstractPlugin<BufferedImage> {

    final private static Logger logger = LoggerFactory.getLogger(ClassifyTilesPlugin.class);

    String lastMessage = null;
   // ParallelInference pi = ClassifyTilesCNNCommand.pi;
    String preProcessingStyle;
    final String imageNetMeanSub = "ImageNet Mean Subtraction";
    final String zeroCenterScaled = "( -1 to 1 ) Scaling";
    int tileSize;
    boolean tileOverlap;
    public static final INDArray VGG_MEAN_OFFSET_BGR = Nd4j.create(new double[] {103.939, 116.779, 123.68 });

    private MeasurementMapper.ColorMapper colorMapper = new MeasurementMapper.PseudoColorMapper();



        public ClassifyTilesPlugin( int tileSize, boolean tileOverlap, String preProcessingStyle) {

            this.tileSize = tileSize;
            this.tileOverlap = tileOverlap;
            //this.pi = pi;
            this.preProcessingStyle = preProcessingStyle;

        }


        @Override
        protected void addWorkflowStep(final ImageData<BufferedImage> imageData, final String arg) {
            // Do nothin
        }

        @Override
        public String getName() {
            return "Classify Tiles with CNN";
        }

        @Override
        public String getDescription() {
            return "Use a CNN to determine class of tiles in selected annotations";
        }

        @Override
        public String getLastResultsDescription() {
            return lastMessage;
        }

        @Override
        protected boolean parseArgument(ImageData<BufferedImage> imageData, String arg) {
            return true;
        }

        public Collection<Class<? extends PathObject>> getSupportedParentObjectClasses() {
            return Arrays.asList(
                    PathAnnotationObject.class,
                    TMACoreObject.class
            );
        }

        @Override
        protected Collection<? extends PathObject> getParentObjects(PluginRunner<BufferedImage> runner) {
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
        protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
            Runnable runnable = new Runnable() {

                @Override
                public void run() {

                    DataNormalization scaler = new ImagePreProcessingScaler(-1, 1);
                    final INDArray VGG_MEAN_OFFSET_BGR = Nd4j.create(new double[] {103.939, 116.779, 123.68 });

                    parentObject.setColorRGB(ColorTools.makeRGB(255, 255, 0));
                    imageData.getHierarchy().fireObjectsChangedEvent(this, Collections.singleton(parentObject), false);

                    ImageServer<BufferedImage> server = imageData.getServer();
                    RegionRequest request = RegionRequest.createInstance(server.getPath(), 1, parentObject.getROI());

                    //Get image object
                    BufferedImage img = server.readRegion(request).getImage();
                    // We Convert Buffered Image Type becasue we want an Image that Java2dNativeImageLoader can read with flip channels true/false depending on the preprocessing
                    // This is because the vgg16ImagePreprocessor doesn't flip the image the same way as the keras one does, meaning we have to do things manually ourselves.
                    BufferedImage imgBGR = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
                    imgBGR.getGraphics().drawImage(img, 0, 0, null);

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

                        double outputProb = ClassifyTilesCNNCommand.pi.output(imageArray).getDouble(0);

                        // Add new measurement to the measurement list of the detection
                        //Todo: link this value to a name to add to the measurement
                        parentObject.getMeasurementList().addMeasurement("Tumour Probablity", outputProb);

                        // It's important for efficiency reasons to close the list
                        parentObject.getMeasurementList().closeList();

                        parentObject.setColorRGB(colorMapper.getColor(outputProb, 0.0, 1.0));
                        imageData.getHierarchy().fireObjectsChangedEvent(this, Collections.singleton(parentObject), true);

                    } catch(IOException ex){
                        lastMessage = "Error getting image from QuPath to classify";
                    }


                }


            };

            tasks.add(runnable);
        }


        @Override
        protected void preprocess(final PluginRunner<BufferedImage> pluginRunner){

            //Run Tiler Plugin
            //CNNTilerPlugin tiler = new TilerPlugin();
            TilerPlugin tiler = new TilerPlugin();
            CommandLinePluginRunner runner = new CommandLinePluginRunner(pluginRunner.getImageData(),false);
            String tilerArgs = String.format("{\"tileSizeMicrons\": %f ,  \"trimToROI\": false,  \"makeAnnotations\": false,  \"removeParentAnnotation\": false}", (double) tileSize * pluginRunner.getImageData().getServer().getPixelWidthMicrons());
            //ToDo: Tile size should either be user selected or (ideally) based on the loaded model's size.
            //ToDo: At the very least there should be a check whether the tile size actually fits the model...
            tiler.runPlugin(runner, tilerArgs);

            PathObjectHierarchy hierarchy = pluginRunner.getHierarchy();
            ArrayList<PathObject> selectedObjects = new ArrayList<>(hierarchy.getSelectionModel().getSelectedObjects());

            PathObjectTools.filterROIs(selectedObjects, PathShape.class);

            if (tileOverlap) {

                for (PathObject boundingObject : selectedObjects) {

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

                        }

                    }

                    if (overlapTiles.size() > 0) {
                        boundingObject.addPathObjects(overlapTiles);
                        pluginRunner.getHierarchy().fireHierarchyChangedEvent(this, boundingObject);
                    }
                }


            }


            Collection<?extends PathObject> tileObjects =  getParentObjects(pluginRunner);
            for(PathObject tile : tileObjects){

                tile.setColorRGB(ColorTools.makeRGB(128,128,128));
            }

        }


        @Override
        protected void postprocess(final PluginRunner<BufferedImage> pluginRunner) {

//        Collection<?extends PathObject> tileObjects =  getParentObjects(pluginRunner);
//        for(PathObject tile : tileObjects){
//
//            tile.setColorRGB(ColorTools.makeRGB(128,128,128));
//        }

        }



    }








