package qupath.dl4j;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.images.servers.ImagePlusServer;
import qupath.imagej.images.servers.ImagePlusServerBuilder;
import qupath.lib.algorithms.TilerPlugin;
import qupath.lib.classifiers.PathClassificationLabellingHelper;
import qupath.lib.classifiers.PathClassifierTools;
import qupath.lib.gui.ImageWriterTools;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.*;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.helpers.PathObjectTools;

import qupath.lib.plugins.AbstractPlugin;
import qupath.lib.plugins.CommandLinePluginRunner;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.AWTAreaROI;
import qupath.lib.roi.RectangleROI;
import scala.Int;

import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

import static qupath.lib.classifiers.PathClassificationLabellingHelper.getRepresentedPathClasses;
import static qupath.lib.roi.PathROIToolsAwt.getArea;

/**
 * Created by cschlosser on 25/01/2018.
 * This plugin creates tiles with greater than 0.80 overlap with their bounding annotations and saves them in directories corresponding to their PathClass
 */


public class SaveCNNTrainingDataPlugin extends AbstractPlugin<BufferedImage> {

    final private static Logger logger = LoggerFactory.getLogger(ClassifyTilesPlugin.class);


    int tileSize;
    boolean tileOverlap;
    String unclassifiedName;
    String lastMessage;
    String outputDir;


    public SaveCNNTrainingDataPlugin( int tileSize, boolean tileOverlap, String unclassifiedName,String outputDir ) {

        this.tileSize = tileSize;
        this.tileOverlap = tileOverlap;
        this.unclassifiedName = unclassifiedName;
        this.outputDir = outputDir;

    }


    @Override
    protected void addWorkflowStep(final ImageData<BufferedImage> imageData, final String arg) {
        // Do nothin
    }

    @Override
    public String getName() {
        return "Save CNN Trining Tiles";
    }

    @Override
    public String getDescription() {
        return "Tile annotations and export the tile images for external CNN training";
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

        Collection<? extends PathObject> objects = PathObjectTools.getSupportedObjects(selectedObjects, supported);

        return objects;

    }

    @Override
    protected void addRunnableTasks(ImageData<BufferedImage> imageData, PathObject parentObject, List<Runnable> tasks) {
        Runnable runnable = new Runnable() {

            @Override
            public void run() {

                //Create image server
                ImageServer<BufferedImage> serverOriginal = imageData.getServer();
                ImagePlusServer server = ImagePlusServerBuilder.ensureImagePlusWholeSlideServer(serverOriginal);

                //Export image name formating
                String ext = ".tif";

                //Downsample factor for images - set to 1 unless otherwise specified
                double downsample = 1.0;

                String serverName = serverOriginal.getShortServerName();
                String path = server.getPath();

                String classSubDir = (parentObject.getPathClass()== null) ? unclassifiedName : parentObject.getPathClass().getName();
                File subDir = new File(outputDir, classSubDir);
                subDir.mkdirs();


                //Default isn't stringent enough so we remove any tile which isn't 80% overlapping with the whole annotaiton

                Collection<PathObject> childrenTiles =  imageData.getHierarchy().getDescendantObjects(parentObject,null,PathTileObject.class);
                Area annotationArea = getArea(parentObject.getROI());
                Collection<PathObject> strideTiles = new ArrayList<>();

                Collection<PathObject> rejectTiles = new ArrayList<>();

                for (PathObject tile : childrenTiles) {

                    Area tileArea = getArea(tile.getROI());
                    AWTAreaROI tileAWTArea = new AWTAreaROI(tileArea);
                    tileArea.intersect(annotationArea);
                    AWTAreaROI intersectionAWTArea = new AWTAreaROI(tileArea);

                    double overlap = intersectionAWTArea.getArea() / tileAWTArea.getArea();
                    if (overlap < 0.8) rejectTiles.add(tile);

                    //If tiles are to be overlapped, do it here:
                    //They must also satisfy 80% overlap rule
                    if (tileOverlap) {

                        RectangleROI tileROI = (RectangleROI) tile.getROI();
                        double tileX = tileROI.getBoundsX();
                        double tileY = tileROI.getBoundsY();
                        double tileWidth = tileROI.getBoundsWidth();
                        double tileHeight = tileROI.getBoundsHeight();

                        //X Stride
                        PathTileObject tempTileX = new PathTileObject(new RectangleROI(tileX + tileSize / 2, tileY, tileWidth, tileHeight));

                        Area tileAreaX = getArea(tempTileX.getROI());
                        AWTAreaROI tileAWTAreaX = new AWTAreaROI(tileAreaX);
                        tileAreaX.intersect(annotationArea);
                        AWTAreaROI intersectionAWTAreaX = new AWTAreaROI(tileAreaX);

                        double overlapX = intersectionAWTAreaX.getArea()/tileAWTAreaX.getArea();
                        if(overlapX>=0.8) {
                            tempTileX.setName(tile.getName() + "_stride-x");
                            strideTiles.add(tempTileX);
                        }

                        //Y Stride
                        PathTileObject tempTileY = new PathTileObject(new RectangleROI(tileX, tileY + tileSize / 2, tileWidth, tileHeight));

                        Area tileAreaY = getArea(tempTileY.getROI());
                        AWTAreaROI tileAWTAreaY = new AWTAreaROI(tileAreaY);
                        tileAreaY.intersect(annotationArea);
                        AWTAreaROI intersectionAWTAreaY = new AWTAreaROI(tileAreaY);

                        double overlapY = intersectionAWTAreaY.getArea()/tileAWTAreaY.getArea();
                        if(overlapY>=0.8) {
                            tempTileY.setName(tile.getName() + "_stride-y");
                            strideTiles.add(tempTileY);
                        }

                        //X-Y Stride
                        PathTileObject tempTileXY = new PathTileObject(new RectangleROI(tileX + tileSize / 2, tileY + tileSize / 2, tileWidth, tileHeight));

                        Area tileAreaXY = getArea(tempTileXY.getROI());
                        AWTAreaROI tileAWTAreaXY = new AWTAreaROI(tileAreaXY);
                        tileAreaY.intersect(annotationArea);
                        AWTAreaROI intersectionAWTAreaXY = new AWTAreaROI(tileAreaXY);

                        double overlapXY = intersectionAWTAreaXY.getArea()/tileAWTAreaXY.getArea();
                        if(overlapXY>=0.8) {
                            tempTileY.setName(tile.getName() + "_stride-xy");
                            strideTiles.add(tempTileY);
                        }

                    }

                }

                parentObject.removePathObjects(rejectTiles);

                if (strideTiles.size() > 0) {
                    parentObject.addPathObjects(strideTiles);

                }


                imageData.getHierarchy().getSelectionModel().clearSelection();

                //Get annotation class name for organizing tiles
                //String className = (parentObject.getPathClass()== null) ? unclassifiedName : parentObject.getPathClass().getName() ;
                String annotationName = parentObject.getName();
                String filename = imageData.getServer().getDisplayedImageName();

                for (PathObject child : parentObject.getChildObjects()) {

                    double outputSize = (double) tileSize;
                    if (child.isTile()) {

                        String frame = child.getDisplayedName();
                        RegionRequest request = RegionRequest.createInstance(path, downsample, child.getROI());
                        String name = String.format("%s_%s_%s%s", filename,annotationName, frame, ext);
                        File file1 = new File(subDir, name);
                        ImageWriterTools.writeImageRegion(imageData.getServer(), request, file1.getAbsolutePath());

                    }

                }


            }

        };

        tasks.add(runnable);
    }


    @Override
    protected void preprocess(final PluginRunner<BufferedImage> pluginRunner){

        //Setup TilerPlugin


        TilerPlugin tiler = new TilerPlugin();
        CommandLinePluginRunner runner = new CommandLinePluginRunner( pluginRunner.getImageData(),false);
        String tilerArgs = String.format("{\"tileSizeMicrons\": %f ,  \"trimToROI\": false,  \"makeAnnotations\": false,  \"removeParentAnnotation\": false}",(double)tileSize*pluginRunner.getImageData().getServer().getPixelWidthMicrons() );




        Collection<? extends PathObject> annotationsToTile = getParentObjects(pluginRunner);

        //Set names for each object, this is necessary so that tiles can be saved with unique names related to the annotations, preventing filename conflicts
        int i = 1;
        for(PathObject exportAnnotation: annotationsToTile){
            if(exportAnnotation.getPathClass() == null){
                exportAnnotation.setName(unclassifiedName + "-" + String.valueOf(i));
                i++;
            }
        }

        //Set names for annotations that are classified
        for(PathClass pathClass : PathClassificationLabellingHelper.getRepresentedPathClasses(pluginRunner.getHierarchy(),null)){

            int f = 1;
            for( PathObject pathClassAnnotation : PathClassificationLabellingHelper.getAnnotationsForClass(pluginRunner.getHierarchy(),pathClass)){
                pathClassAnnotation.setName( pathClass.getName() + "-" + String.valueOf(f) );
                f++;
            }

        }

        //Tile each annotation
        for( PathObject exportAnnotation : annotationsToTile){

            Collection<PathObject> childrenToSave = new ArrayList<>();
            childrenToSave.addAll(pluginRunner.getHierarchy().getDescendantObjects(exportAnnotation,null,PathAnnotationObject.class));

            pluginRunner.getHierarchy().getSelectionModel().setSelectedObject(exportAnnotation);
            tiler.runPlugin(runner, tilerArgs);

        }

        //Since getParentObject filters based on selection, and the tiler modifies the selection, we need to reselect our parent objects here
        pluginRunner.getHierarchy().getSelectionModel().selectObjects(annotationsToTile);

    }


    @Override
    protected void postprocess(final PluginRunner<BufferedImage> pluginRunner) {

        pluginRunner.getHierarchy().fireHierarchyChangedEvent(this);

    }



}








