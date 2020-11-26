package qupath.LMDExport;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.classifiers.PathClassificationLabellingHelper;
import qupath.lib.gui.helpers.DisplayHelpers;
import qupath.lib.images.ImageData;
import qupath.lib.objects.*;


import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.plugins.AbstractInteractivePlugin;
import qupath.lib.plugins.PluginRunner;
import qupath.lib.plugins.parameters.ParameterList;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;


/**
 * Created by cschlosser on 01/06/2018.
 */
public class CellFractionPlugin extends AbstractInteractivePlugin<BufferedImage> {


    final private static Logger logger = LoggerFactory.getLogger(ExportLMDMapPlugin.class);

    private ParameterList params;
    private boolean parametersInitialized = false;

    volatile int cellCount = 0;
    volatile int cellOfInterestCount = 0;

    public CellFractionPlugin () {

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

        PathClass selectedPathClass = (PathClass)params.getChoiceParameterValue("pathClass");

        Runnable runnable =  new Runnable() {

            @Override
            public void run() {


               int annotationCellCount = PathObjectTools.countChildren(parentObject, PathCellObject.class, true);
               int annotationCellOfInterestCount = PathObjectTools.countChildren(parentObject, selectedPathClass, true);

                double cellOfInterestFraction = (double)annotationCellOfInterestCount/(double)annotationCellCount;

                String selectedPathClassName = ((PathClass)params.getChoiceParameterValue("pathClass")).getName();

                DisplayHelpers.showPlainMessage(selectedPathClassName +" Fraction",String.format("Total cells within area: %d \n %s cells within area: %d \n %s Fraction: %f %%",cellCount,selectedPathClassName,cellOfInterestCount,selectedPathClassName,cellOfInterestFraction));


            }


        };

        tasks.add(runnable);
    }


    @Override
    public ParameterList getDefaultParameterList(final ImageData<BufferedImage> imageData) {
        if (!parametersInitialized) {
            Set<PathClass> pathClasses = PathClassificationLabellingHelper.getRepresentedPathClasses(imageData.getHierarchy(), PathCellObject.class);
            if(pathClasses.size()<1){
                logger.error("No cell objects in selected region");
                return null;
            }

            List<PathClass> choices = new ArrayList<>(pathClasses);

            PathClass classTumor = PathClassFactory.getDefaultPathClass(PathClassFactory.PathClasses.TUMOR); // Tumor is the most likely choice, so default to it if available
			PathClass defaultChoice = choices.contains(classTumor) ? classTumor : choices.get(0);

            params = new ParameterList();
            params.addChoiceParameter("pathClass", "Calculate fraction for which type of cell?", defaultChoice, choices, "Choose PathClass to create annotations from");

        }
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

        Collection<? extends PathObject> validParentobjects = PathObjectTools.getSupportedObjects(selectedObjects, supported);

        return validParentobjects;
    }


    @Override
    protected void addWorkflowStep(final ImageData<BufferedImage> imageData, final String arg){
    }

    @Override
    protected void preprocess(final PluginRunner<BufferedImage> pluginRunner){

//        outputFile = QuPathGUI.getSharedDialogHelper().promptToSaveFile( "XML file",null,pluginRunner.getImageData().getServer().getDisplayedImageName()+"_leica-map","xml files","xml");
//
//        PathObjectHierarchy hierarchy = pluginRunner.getHierarchy();
//        ArrayList<PathObject> selectedObjects = new ArrayList<>(hierarchy.getSelectionModel().getSelectedObjects());
//
//        PathObjectTools.filterROIs(selectedObjects, PathShape.class);
//
//        hierarchy.getSelectionModel().clearSelection();
//        hierarchy.getSelectionModel().selectObjects(selectedObjects);


    }

    @Override
    protected void postprocess(final PluginRunner<BufferedImage> pluginRunner) {
        //Todo: Find and display the aggregate percent?

    }

}

