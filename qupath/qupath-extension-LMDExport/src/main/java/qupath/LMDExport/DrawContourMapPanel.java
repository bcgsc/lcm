package qupath.LMDExport;

/**
 * Created by cschlosser on 14/09/2017.
 * Panel created to help visualise where contours will be drawn based on certain measuements / level inputs.
 * This class is almost a direct copy of QuPath's MeasurementMapPanel with the addition of the method used
 * to draw the contours.
 */



import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.classifiers.PathClassificationLabellingHelper;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.helpers.DisplayHelpers;
import qupath.lib.gui.helpers.MeasurementMapper;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;

import java.awt.image.BufferedImage;
import java.util.*;

import java.util.Collection;
import java.util.HashMap;
import java.util.Set;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;

import qupath.lib.objects.PathDetectionObject;
import qupath.lib.objects.PathObject;
import qupath.lib.plugins.PluginRunnerFX;



// TODO: Revise MeasurementMapPanel whenever multiple viewers are present
public class DrawContourMapPanel {


    enum ThresholdLevel{
        LOW,
        MID,
        HIGH
    }

    private final static Logger logger = LoggerFactory.getLogger(qupath.lib.gui.panels.MeasurementMapPanel.class);

    private QuPathGUI qupath;

    private HashMap<String, ContourMeasurementMapper> mapperMap = new HashMap<>();

    private BorderPane pane = new BorderPane();

    private ObservableList<String> baseList = FXCollections.observableArrayList();
    private FilteredList<String> filteredList = new FilteredList<>(baseList);
    private ListView<String> listMeasurements = new ListView<>(filteredList);

    private int sliderRange = 200;
    private Slider sliderMin = new Slider(0, sliderRange, 0.05 * sliderRange);
    private Slider sliderMax = new Slider(0, sliderRange, sliderRange *0.95);

    private int maxAreaFilter = 50000;
    private Slider sliderFilter = new Slider(0, maxAreaFilter, 0);

    // For not painting values outside the mapper range
    private CheckBox cbExcludeOutside = new CheckBox("Exclude outside range");

    private Canvas colorMapperKey;
    private Image colorMapperKeyImage;

    private Label labelMin = new Label("");
    private Label labelMax = new Label("");
    private Label labelFilterName = new Label("Min Contour Area [um^2]");
    private Label labelFilterValue = new Label("");

    final ToggleGroup rbGroup = new ToggleGroup();
    private RadioButton rbContourHigh;
    private RadioButton rbContourMid;
    private RadioButton rbContourLow;
    ThresholdLevel rbThreshSelection;
    private CheckBox chkHoles;


    private ContourMeasurementMapper mapper = null;

    private List<PathObject> pathObjects = null;
    private List<PathObject> validBoundingPathObjects = null;



    public DrawContourMapPanel(final QuPathGUI qupath) {
        this.qupath = qupath;

        updateMeasurements();

        cbExcludeOutside.setSelected(true);

        final ToggleButton toggleShowMap = new ToggleButton("Show map");
        toggleShowMap.setTooltip(new Tooltip("Show/hide the map"));
        toggleShowMap.setSelected(true);
        toggleShowMap.setOnAction(e -> {
            if (toggleShowMap.isSelected())
                showMap();
            else
                hideMap();
        });

        //listMeasurements.getSelectionModel().select(0);
        listMeasurements.getSelectionModel().selectedItemProperty().addListener((e, f, g) -> {
                    if (toggleShowMap.isSelected())
                        showMap();
                }
        );
        listMeasurements.setTooltip(new Tooltip("List of available measurements"));

        pane.setCenter(listMeasurements);

        cbExcludeOutside.selectedProperty().addListener((e, f, g) -> updateDisplay());

        sliderMin.valueProperty().addListener((e, f, g) -> updateDisplay());
        sliderMax.valueProperty().addListener((e, f, g) -> updateDisplay());


        sliderMin.setTooltip(new Tooltip("Min display value"));
        sliderMax.setTooltip(new Tooltip("Max display value"));

        BorderPane panelLabels = new BorderPane();
        labelMin.setTextAlignment(TextAlignment.RIGHT);
        labelMin.setTextAlignment(TextAlignment.LEFT);
        panelLabels.setLeft(labelMin);
        panelLabels.setRight(labelMax);

        BorderPane panelFilterLabels = new BorderPane();
        labelFilterName.setTextAlignment(TextAlignment.LEFT);
        labelFilterValue.setTextAlignment(TextAlignment.RIGHT);
        panelFilterLabels.setLeft(labelFilterName);
        panelFilterLabels.setRight(labelFilterValue);

        Button btnRefresh = new Button("Update map");
        btnRefresh.setTooltip(new Tooltip("Update map data & recompute the min/max settings used to display colors"));
        btnRefresh.setOnAction(e -> {
                    updateMeasurements();
                    mapperMap.clear();
                    if (toggleShowMap.isSelected())
                        showMap();
                }
        );


        Button btnDrawGridding = new Button("Draw Gridding Contour");
        btnDrawGridding.setTooltip(new Tooltip("Close the dialog and draw annotations around the selected detection objects by using gridded interpolation to create a topography and find contour lines"));
        btnDrawGridding.setOnAction(e -> {
                    drawContoursGridding();
                    hideMap();
                    Stage stage = (Stage) btnDrawGridding.getScene().getWindow();
                    stage.close();
                }
        );

        Button btnDrawThreshold = new Button("Draw Threshold Contour");
        btnDrawThreshold.setTooltip(new Tooltip("Close dialog and  draw annotations around the selected detection objects by thresholding the measurement"));
        btnDrawThreshold.setOnAction(e -> {
                    drawContoursThreshold();
                    hideMap();
                    Stage stage = (Stage) btnDrawThreshold.getScene().getWindow();
                    stage.close();
                }
        );


        sliderFilter.valueProperty().addListener((e, f, g) -> updateFilter());
        sliderFilter.setTooltip(new Tooltip("Min Area to Contour"));
        sliderFilter.setMajorTickUnit(1000);
        sliderFilter.setMinorTickCount(0);
        sliderFilter.setSnapToTicks(true);

        chkHoles = new CheckBox("Keep Holes?");
        chkHoles.setSelected(true);   // Default to keeping holes


        rbContourHigh = new RadioButton("Contour High (Red)");
        rbContourHigh.setToggleGroup(rbGroup);
        rbContourHigh.setOnAction(e->{
            rbThreshSelection = ThresholdLevel.HIGH;
            btnDrawGridding.setDisable(false);

        });
        rbContourHigh.setSelected(true);
        rbThreshSelection = ThresholdLevel.HIGH;


        rbContourMid = new RadioButton("Contour Mid (Grey)");
        rbContourMid.setToggleGroup(rbGroup);
        rbContourMid.setOnAction(e -> {
            rbThreshSelection = ThresholdLevel.MID;
            btnDrawGridding.setDisable(true);
        }
        );

        rbContourLow = new RadioButton("Contour Low (Blue)");
        rbContourLow.setToggleGroup(rbGroup);
        rbContourLow.setOnAction(e->{
            rbThreshSelection = ThresholdLevel.LOW;
            btnDrawGridding.setDisable(false);
        });

        BorderPane panelContourButtons = new BorderPane();
        panelContourButtons.setRight(rbContourHigh);
        panelContourButtons.setCenter(rbContourMid);
        panelContourButtons.setLeft(rbContourLow);




        double canvasHeight = 10;
        colorMapperKey = new Canvas() {
            @Override
            public double minHeight(double width) {
                return canvasHeight;
            }

            @Override
            public double maxHeight(double width) {
                return canvasHeight;
            }

            @Override
            public double prefHeight(double width) {
                return canvasHeight;
            }

            @Override
            public double minWidth(double width) {
                return 0;
            }

            @Override
            public double maxWidth(double width) {
                return Double.MAX_VALUE;
            }

            @Override
            public boolean isResizable() {
                return true;
            }

            @Override
            public void resize(double width, double height)	{
                super.setWidth(width);
                super.setHeight(height);
                updateColorMapperKey();
            }
        };
        Tooltip.install(colorMapperKey, new Tooltip("Measurement map key"));

        // Filter to reduce visible measurements
        TextField tfFilter = new TextField();
        tfFilter.setTooltip(new Tooltip("Enter text to filter measurement list"));
        tfFilter.textProperty().addListener((v, o, n) -> {
            String val = n.trim().toLowerCase();
            filteredList.setPredicate(s -> {
                if (val.isEmpty())
                    return true;
                return s.toLowerCase().contains(val);
            });
        });


        BorderPane paneFilter = new BorderPane();
        paneFilter.setPadding(new Insets(5, 0, 10, 0));
        paneFilter.setCenter(tfFilter);


        BorderPane paneSizeFilter = new BorderPane();
        paneSizeFilter.setPadding(new Insets(20, 0, 0, 0));
        paneSizeFilter.setCenter(sliderFilter);

        VBox vbButtons = new VBox(
                paneFilter,
                sliderMin,
                sliderMax,
                colorMapperKey,
                panelLabels,
                btnRefresh,
                toggleShowMap,
                paneSizeFilter,
                panelFilterLabels,
                panelContourButtons,
                chkHoles,
                btnDrawGridding,
                btnDrawThreshold

        );

        vbButtons.setSpacing(5);

        sliderMin.setMaxWidth(Double.MAX_VALUE);
        sliderMax.setMaxWidth(Double.MAX_VALUE);
        panelLabels.setMaxWidth(Double.MAX_VALUE);
        btnRefresh.setMaxWidth(Double.MAX_VALUE);
        toggleShowMap.setMaxWidth(Double.MAX_VALUE);
        sliderFilter.setMaxWidth(Double.MAX_VALUE);
        panelFilterLabels.setMaxWidth(Double.MAX_VALUE);
        btnDrawGridding.setMaxWidth(Double.MAX_VALUE);
        btnDrawThreshold.setMaxWidth(Double.MAX_VALUE);
        chkHoles.setMaxWidth(Double.MAX_VALUE);
        vbButtons.setFillWidth(true);

//		GridPane.setHgrow(colorMapperKey, Priority.ALWAYS);

        pane.setBottom(vbButtons);
        pane.setPadding(new Insets(10, 10, 10, 10));

    }

    public Pane getPane() {
        return pane;
    }

    public void setPathObjects(List<PathObject> pathObjects, List<PathObject> validBoundingPathObjects) {
        this.pathObjects = pathObjects;
        this.validBoundingPathObjects = validBoundingPathObjects;
    }

    private void drawContoursGridding()
    {

        if(listMeasurements.getSelectionModel().isEmpty()){
            DisplayHelpers.showErrorMessage("No Measurement","No measurement selected.");
            return;
        }

        String selectedMeasurement = listMeasurements.getSelectionModel().getSelectedItem();
        double areaFilter =  sliderFilter.getValue();
        double minValueSlider = (double)sliderMin.getValue() / sliderRange;
        double maxValueSlider = (double)sliderMax.getValue() / sliderRange;
        double minValue = mapper.getDataMinValue() + (mapper.getDataMaxValue() - mapper.getDataMinValue()) * minValueSlider;
        double maxValue = mapper.getDataMinValue() + (mapper.getDataMaxValue() - mapper.getDataMinValue()) * maxValueSlider;


        DrawGriddedContourPlugin drawGriddedContourPlugin = new DrawGriddedContourPlugin(selectedMeasurement,areaFilter,minValue,maxValue,true,chkHoles.isSelected());
        PluginRunnerFX runner = new PluginRunnerFX(qupath,false);
        new Thread(() -> drawGriddedContourPlugin.runPlugin(runner, null)).start();


    }

    private void drawContoursThreshold()
    {

        if(listMeasurements.getSelectionModel().isEmpty()){
            DisplayHelpers.showErrorMessage("No Measurement","No measurement selected.");
            return;
        }

        String selectedMeasurement = listMeasurements.getSelectionModel().getSelectedItem();
        double areaFilter =  sliderFilter.getValue();
        double minValueSlider = (double)sliderMin.getValue() / sliderRange;
        double maxValueSlider = (double)sliderMax.getValue() / sliderRange;
        double minValue = mapper.getDataMinValue() + (mapper.getDataMaxValue() - mapper.getDataMinValue()) * minValueSlider;
        double maxValue = mapper.getDataMinValue() + (mapper.getDataMaxValue() - mapper.getDataMinValue()) * maxValueSlider;

        DrawThresholdContourPlugin drawThresholdContourPlugin = new DrawThresholdContourPlugin(selectedMeasurement,areaFilter,minValue,maxValue,rbThreshSelection,chkHoles.isSelected());
        PluginRunnerFX runner = new PluginRunnerFX(qupath,false);
        new Thread(() -> drawThresholdContourPlugin.runPlugin(runner, null)).start();


    }



    public void showMap() {
        String measurement = listMeasurements.getSelectionModel().getSelectedItem();
        QuPathViewer viewer = qupath.getViewer();
        if (viewer == null || measurement == null)
            return;
        // Reuse mappers if we can
        mapper = mapperMap.get(measurement);
        if (mapper == null) {
            mapper = new ContourMeasurementMapper(measurement, viewer.getHierarchy().getObjects(null, null));
            if (mapper.isValid())
                mapperMap.put(measurement, mapper);
        }

        colorMapperKeyImage = createPanelKey(mapper.getColorMapper());
        updateColorMapperKey();
        mapper.setExcludeOutsideRange(cbExcludeOutside.isSelected());
        viewer.forceOverlayUpdate();
        updateMapperBrightnessContrast();
        OverlayOptions overlayOptions = viewer.getOverlayOptions();
        if (overlayOptions != null)
            overlayOptions.setMeasurementMapper(mapper);
    }


    private void updateColorMapperKey() {
        GraphicsContext gc = colorMapperKey.getGraphicsContext2D();
        double w = colorMapperKey.getWidth();
        double h = colorMapperKey.getHeight();
        gc.clearRect(0, 0, w, h);
        if (colorMapperKeyImage != null)
            gc.drawImage(colorMapperKeyImage,
                    0, 0, colorMapperKeyImage.getWidth(), colorMapperKeyImage.getHeight(),
                    0, 0, w, h);
    }


    public void updateMapperBrightnessContrast() {
        if (mapper == null)
            return;
        double minValueSlider = (double)sliderMin.getValue() / sliderRange;
        double maxValueSlider = (double)sliderMax.getValue() / sliderRange;

        double minValue = mapper.getDataMinValue() + (mapper.getDataMaxValue() - mapper.getDataMinValue()) * minValueSlider;
        double maxValue = mapper.getDataMinValue() + (mapper.getDataMaxValue() - mapper.getDataMinValue()) * maxValueSlider;

        labelMin.setText(String.format("%.2f", minValue));
        labelMax.setText(String.format("%.2f", maxValue));

        mapper.setDisplayMinValue(minValue);
        mapper.setDisplayMaxValue(maxValue);
        mapper.setExcludeOutsideRange(cbExcludeOutside.isSelected());
    }



    public void hideMap() {
        QuPathViewer viewer = qupath.getViewer();
        if (viewer != null) {
            OverlayOptions overlayOptions = viewer.getOverlayOptions();
            if (overlayOptions != null)
                overlayOptions.resetMeasurementMapper();
//			viewer.resetMeasurementMapper();
        }
    }


    public void updateMeasurements() {
//		this.measurements.clear();
//		this.measurements.addAll(measurements);

        QuPathViewer viewer = qupath.getViewer();
        PathObjectHierarchy hierarchy = viewer.getHierarchy();
        if (hierarchy == null) {
            baseList.clear();
            return;
        }

        Collection<PathObject> pathObjects = hierarchy.getObjects(null, PathDetectionObject.class);
        Set<String> measurements = PathClassificationLabellingHelper.getAvailableFeatures(pathObjects);
        for (PathObject pathObject : pathObjects) {
            if (!Double.isNaN(pathObject.getClassProbability())) {
                measurements.add("Class probability");
                break;
            }
        }

        // Apply any changes
        baseList.setAll(measurements);
    }



    public void updateDisplay() {
        QuPathViewer viewer = qupath.getViewer();
        updateMapperBrightnessContrast();
        viewer.forceOverlayUpdate();
//		viewer.repaint();
    }



    static Image createPanelKey(final MeasurementMapper.ColorMapper colorMapper) {
        BufferedImage imgKey = new BufferedImage(255, 10, BufferedImage.TYPE_INT_ARGB);
        if (colorMapper != null) {
            for (int i = 0; i < imgKey.getWidth(); i++) {
                Integer rgb = colorMapper.getColor(i, 0, 254);
                for (int j = 0; j < imgKey.getHeight(); j++) {
                    imgKey.setRGB(i, j, rgb);
                }
            }
        }
        Image img = SwingFXUtils.toFXImage(imgKey, null);
        return img;
    }

    void updateFilter()
    {
        labelFilterValue.setText(String.format("%.0f", sliderFilter.getValue()));

    }

}
