package qupath.dl4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.imagej.images.servers.ImagePlusServer;
import qupath.imagej.images.servers.ImagePlusServerBuilder;
import qupath.lib.algorithms.TilerPlugin;
import qupath.lib.classifiers.PathClassificationLabellingHelper;
import qupath.lib.gui.ImageWriterTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.helpers.DisplayHelpers;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServer;
import qupath.lib.objects.PathAnnotationObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.PathTileObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.PluginRunnerFX;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.AWTAreaROI;
import qupath.lib.roi.PathObjectToolsAwt;
import qupath.lib.roi.PathROIToolsAwt;
import qupath.lib.roi.RectangleROI;


import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

import static qupath.lib.roi.PathROIToolsAwt.*;

/**
 * Created by cschlosser on 01/06/2017.
 */
public class SaveCNNTrainingDataCommand implements PathCommand {

    final private static Logger logger = LoggerFactory.getLogger(SaveCNNTrainingDataCommand.class);

    private QuPathGUI qupath;
    //private ImageData<BufferedImage> imageData;
    //private PathObjectHierarchy hierarchy;

    public SaveCNNTrainingDataCommand(QuPathGUI qupath){

        this.qupath = qupath;

    }

    @Override
    public void run() {

        /* Our aim here is to decompose relevant image areas (tissue detection) into tiles and divide them based on
        * whether they are marked as tumour or part of the rest of the tissue
        */

        //Input State: tissue detection performed and Tumour annotations made

        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null || viewer.getServer() == null)
        {
            logger.error("No Slide Loaded.");
            return;
        }

        PathObjectHierarchy hierarchy = viewer.getHierarchy();

        ImageData currentImageData = qupath.getViewer().getImageData();
        if(currentImageData == null)
        {
            logger.error("Load slide and set up annotations before running script!");
            return;
        }

        ///Collect  names of classes with annotations
        ArrayList<String> exportChoices = new ArrayList<>();
        Set<PathClass> pathClasses = PathClassificationLabellingHelper.getRepresentedPathClasses(qupath.getViewer().getHierarchy(), PathAnnotationObject.class);
        for(PathClass annotationClass : pathClasses){
            exportChoices.add(annotationClass.getName());
        }

        exportChoices.add("All Annotations");
        String defaultChoice = "Selected Annotations";
        exportChoices.add(defaultChoice);


        ParameterList params = new ParameterList();
        params.addIntParameter("tileSize", "Select tile size in pixels?",100,"Px",10,2500)
                .addBooleanParameter("tileOverlap", "Overlap half-tile width?", false, "Selecting yes will create tiles every 1/2 tile size instead of adjacent" )
                .addBooleanParameter("classifyTiles","Classify resulting tiles?",false, "Use the parent class to classify all of the tiles created within it.");
        params.addChoiceParameter("annotationChoice","Class to export",defaultChoice,exportChoices)
                .addBooleanParameter("subtractArea", "Subtract inner annotations from outer annotations?", false);
        params.addStringParameter("defaultDir","Directory for tiles without class","unclassified","Any tiles created within annotations that do not have a class will be stored in this sub-directory.");


        boolean inputSuccess = DisplayHelpers.showParameterDialog("Tile Export",params);
        if(!inputSuccess){return;}

        //Parse inputs
        int tileSize = params.getIntParameterValue("tileSize");
        boolean tileOverlap = params.getBooleanParameterValue("tileOverlap");
        boolean classifyTiles = params.getBooleanParameterValue("classifyTiles");
        String annotationChoice = (String)params.getChoiceParameterValue("annotationChoice");
        boolean subtractArea = params.getBooleanParameterValue("subtractArea");
        String defaultDir = params.getStringParameterValue("defaultDir");


        //Create image server
        ImageServer<BufferedImage> serverOriginal = currentImageData.getServer();
        ImagePlusServer server = ImagePlusServerBuilder.ensureImagePlusWholeSlideServer(serverOriginal);

        //Export image name formating
        String ext = ".tif";

        //Downsample factor for images - set to 1 unless otherwise specified
        double downsample = 1.0;

        String serverName = serverOriginal.getShortServerName();
        String path = server.getPath();

        //Parse selection and get a collection of objects to tile and export
        ArrayList<PathObject> exportAnnotations = new ArrayList<>();
        ArrayList<PathObject> certainAnnotations =  new ArrayList<>( hierarchy.getObjects(null, PathAnnotationObject.class) );
        switch(annotationChoice){
            case "All Annotations":
                exportAnnotations.addAll(certainAnnotations);
                break;
            case "Selected Annotations":
                List<PathObject> validPathObjects = new ArrayList<>(viewer.getAllSelectedObjects());
                Predicate<PathObject> invalidObject = p -> !p.isAnnotation() || p.getROI() == null;
                validPathObjects.removeIf(invalidObject);
                exportAnnotations.addAll(validPathObjects);
                break;
            default:
                Predicate<PathObject> noPathClass = p -> p.getPathClass()==null;
                Predicate<PathObject> wrongPathClass = p -> !annotationChoice.equals(p.getPathClass().getName());
                certainAnnotations.removeIf(noPathClass);
                certainAnnotations.removeIf(wrongPathClass);
                exportAnnotations.addAll(certainAnnotations);
                break;


        }

        if (exportAnnotations.isEmpty() )
        {
            logger.error("No valid annotations selected to export.");
            return;
        }



        if(subtractArea) {

            //get all the other annotations so that we can collect the specified annotations after they are modified by the subtraction and the hierarchy updated
            ArrayList<PathObject> remainingAnnotations = new ArrayList<>(hierarchy.getObjects(null,PathAnnotationObject.class));
            remainingAnnotations.removeAll(exportAnnotations);

            PathROIToolsAwt.CombineOp opSub = PathROIToolsAwt.CombineOp.SUBTRACT;
            PathROIToolsAwt.CombineOp opAdd = PathROIToolsAwt.CombineOp.ADD;

            for(PathObject exportAnnotation : exportAnnotations) {
                Collection<PathObject> exportRegionChildren = hierarchy.getDescendantObjects(exportAnnotation,null,PathAnnotationObject.class);

                List<PathObject> subList = new ArrayList();
                List<PathObject> addList = new ArrayList();
                for(PathObject childToSubtract: exportRegionChildren){
                        addList.add(new PathAnnotationObject(childToSubtract.getROI().duplicate(), exportAnnotation.getPathClass()));
                }

                PathObjectToolsAwt.combineAnnotations(hierarchy, addList, opAdd);
                Collection<PathObject> newChildren = hierarchy.getDescendantObjects(exportAnnotation,null,PathAnnotationObject.class);
                newChildren.removeAll(exportRegionChildren);  //Subtract original children
                subList.addAll(newChildren);
                subList.add(exportAnnotation);
                if(subList.size()>1) PathObjectToolsAwt.combineAnnotations(hierarchy, subList, opSub);
//                if(exportAnnotation.getParent()!= null && exportAnnotation.getParent().isAnnotation()){
//                         PathAnnotationObject parentAnnotation = (PathAnnotationObject) exportAnnotation.getParent();
//                         PathObject annotationDuplicate = new PathAnnotationObject(exportAnnotation.getROI().duplicate(), exportAnnotation.getPathClass());
//                         hierarchy.addPathObject(annotationDuplicate, false,true);
//
//                         List<PathObject> subList = new ArrayList();
//                         subList.add(annotationDuplicate);
//                         subList.add(parentAnnotation);
//                         PathObjectToolsAwt.combineAnnotations(hierarchy, subList, opSub);
//                }
            }

            exportAnnotations.removeAll(exportAnnotations);
            exportAnnotations.addAll(hierarchy.getObjects(null,PathAnnotationObject.class));
            exportAnnotations.removeAll(remainingAnnotations);
        }


        //Select Output Directory
        File directory = qupath.getDialogHelper().promptForDirectory(null);
        if(directory == null)
        {
            logger.error("No output directory selected.");
            return;
        }
        String dirOutput = directory.toString();
        String filename = qupath.getViewer().getServer().getDisplayedImageName();

        for(PathObject annotation : exportAnnotations){

            String classSubDir = (annotation.getPathClass()== null) ? defaultDir : annotation.getPathClass().getName();
            File subDir = new File(dirOutput, classSubDir);
            subDir.mkdirs();
        }

        //Setup TilerPlugin
        TilerPlugin tiler = new TilerPlugin();
        PluginRunnerFX runner = new PluginRunnerFX(qupath,false);
        String tilerArgs = String.format("{\"tileSizeMicrons\": %f ,  \"trimToROI\": false,  \"makeAnnotations\": false,  \"removeParentAnnotation\": false}",(double)tileSize*qupath.getViewer().getServer().getPixelWidthMicrons() );

        //Export tile images
        currentImageData = qupath.getViewer().getImageData();
        OverlayOptions overlayOptions = qupath.getViewer().getOverlayOptions();
        overlayOptions.setShowObjects(false);
        overlayOptions.setShowAnnotations(false);

        //Tiler plugin kills inner annotations so we save them here and add them back
        //It makes more sense for the default behaviour to keep them.

        Collection<PathObject> childrenToSave = new ArrayList<>();

        for(PathObject annotation: exportAnnotations) {

            childrenToSave.addAll(hierarchy.getDescendantObjects(annotation,null,PathAnnotationObject.class));

            qupath.getViewer().setSelectedObject(annotation);
            tiler.runPlugin(runner, tilerArgs);

            //Default isn't stringent enough so we remove any tile which isn't 80% overlapping with the whole annotaiton

            Collection<PathObject> childrenTiles =  hierarchy.getDescendantObjects(annotation,null,PathTileObject.class);
            Area annotationArea = getArea(annotation.getROI());
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
                        tempTileY.setName(tile.getName() + "_stride-y");
                        strideTiles.add(tempTileY);
                    }

                }

//                double overlap = intersectionAWTArea.getArea() / tileAWTArea.getArea();
//                if (overlap < 0.8) hierarchy.removeObject(tile, false);

            }

            annotation.removePathObjects(rejectTiles);

            if (strideTiles.size() > 0) {
                annotation.addPathObjects(strideTiles);
                //hierarchy.fireHierarchyChangedEvent(this, annotation);
            }


            hierarchy.addPathObjects(childrenToSave,true);

            qupath.getViewer().setSelectedObject(null);

            //Get annotation class name for organizing tiles
            String className = (annotation.getPathClass()== null) ? defaultDir : annotation.getPathClass().getName() ;

            for (PathObject child : annotation.getChildObjects()) {

                double outputSize = (double) tileSize;
                if (child.isTile()) {

                    String frame = child.getDisplayedName();
                    RegionRequest request = RegionRequest.createInstance(path, downsample, child.getROI());
                    String name = String.format("%s_%s_%s%s", filename,className, frame, ext);
                    File file1 = new File(dirOutput + File.separator + className, name);
                    ImageWriterTools.writeImageRegionWithOverlay(currentImageData, overlayOptions, request, file1.getAbsolutePath());

                }

            }


        }

        overlayOptions.setShowObjects(true);
        overlayOptions.setShowAnnotations(true);
        logger.info("test data export complete");

    }

}
