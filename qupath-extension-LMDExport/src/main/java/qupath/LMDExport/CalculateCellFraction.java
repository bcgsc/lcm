package qupath.LMDExport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.classifiers.PathClassificationLabellingHelper;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.commands.interfaces.PathCommand;
import qupath.lib.gui.helpers.DisplayHelpers;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.objects.PathCellObject;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.classes.PathClassFactory;
import qupath.lib.objects.helpers.PathObjectTools;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.plugins.parameters.ParameterList;


import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;


/**
 * Created by cschlosser on 29/09/2017.
 *
 * This simple PathCommand tallies up cell objects of a selected type to get an aggregate over all selected annotations
 */
public class CalculateCellFraction implements PathCommand {


    private QuPathGUI qupath;
    final private static Logger logger = LoggerFactory.getLogger(DrawContourMap.class);


    public CalculateCellFraction(QuPathGUI qupath) {

        this.qupath = qupath;

    }

    public void run() {

        //Check for valid viewer
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null || viewer.getServer() == null) {
            logger.error("No Slide Loaded.");
            return;
        }

        //Get selected objects and filter such that we are left with annotations and cores that have an ROI
        //This may not be the best way to filter objects
        PathObjectHierarchy hierarchy = viewer.getHierarchy();
        List<PathObject> validSelectedPathObjects = new ArrayList<>(viewer.getAllSelectedObjects());
        Predicate<PathObject> invalidObject = p -> !(p.isAnnotation() || p.isTMACore()) || p.getROI() == null;
        validSelectedPathObjects.removeIf(invalidObject);

        if (validSelectedPathObjects.isEmpty()) {
            logger.error("No valid annotations from which to calculate cell fraction.");
            return;
        }

        //Of the cell objects, find the set of PathClasses in use
        Set<PathClass> pathClasses = PathClassificationLabellingHelper.getRepresentedPathClasses(hierarchy, PathCellObject.class);
        if(pathClasses.size()<1){
            logger.error("No classified cell objects in selected region");
            return;
        }

        List<PathClass> choices = new ArrayList<>(pathClasses);

        //Setup parameter list
        PathClass classTumor = PathClassFactory.getDefaultPathClass(PathClassFactory.PathClasses.TUMOR); // Tumor is the most likely choice, so default to it if available
        PathClass defaultChoice = choices.contains(classTumor) ? classTumor : choices.get(0);
        ParameterList params = new ParameterList();
        params = new ParameterList();
        params.addChoiceParameter("pathClass", "Calculate fraction for which type of cell?", defaultChoice, choices, "Choose PathClass to create annotations from");

        boolean inputSuccess = DisplayHelpers.showParameterDialog("Calculate Cell Fraction",params);
        if(!inputSuccess){return;}

        //Parse inputs
        PathClass selectedPathClass = (PathClass)params.getChoiceParameterValue("pathClass");


        int cellCount = 0;
        int cellOfInterestCount = 0;

        for(PathObject pathObjectToCount: validSelectedPathObjects) {

            cellCount += PathObjectTools.countChildren(pathObjectToCount, PathCellObject.class, true);
            cellOfInterestCount +=  PathObjectTools.countChildren(pathObjectToCount, selectedPathClass, true);

        }


        double cellOfInterestFraction = (double)cellOfInterestCount / (double)cellCount;
        String selectedPathClassName = ((PathClass) params.getChoiceParameterValue("pathClass")).getName();
        DisplayHelpers.showPlainMessage("Aggregate" + selectedPathClassName + " Fraction", String.format("Total cells within area: %d \n %s cells within area: %d \n %s Fraction: %f %%", cellCount, selectedPathClassName, cellOfInterestCount, selectedPathClassName, cellOfInterestFraction));

        //Todo: Can we make a nice table to show individual annotations?

    }


}
