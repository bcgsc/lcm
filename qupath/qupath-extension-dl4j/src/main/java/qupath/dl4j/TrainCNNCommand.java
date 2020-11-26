package qupath.dl4j;

import javafx.stage.DirectoryChooser;
import org.apache.commons.io.FilenameUtils;
import org.datavec.api.io.filters.BalancedPathFilter;
import org.datavec.api.io.labels.ParentPathLabelGenerator;
import org.datavec.api.split.FileSplit;
import org.datavec.api.split.InputSplit;
import org.datavec.image.loader.NativeImageLoader;
import org.datavec.image.recordreader.ImageRecordReader;
import org.datavec.image.transform.FlipImageTransform;
import org.datavec.image.transform.ImageTransform;
import org.datavec.image.transform.WarpImageTransform;
import org.deeplearning4j.datasets.datavec.RecordReaderDataSetIterator;
import org.deeplearning4j.datasets.iterator.MultipleEpochsIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.conf.CacheMode;
import org.deeplearning4j.nn.conf.WorkspaceMode;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.conf.preprocessor.CnnToFeedForwardPreProcessor;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.transferlearning.FineTuneConfiguration;
import org.deeplearning4j.nn.transferlearning.TransferLearning;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.optimize.listeners.ScoreIterationListener;
import org.deeplearning4j.util.ModelSerializer;
import org.deeplearning4j.api.storage.StatsStorage;
import org.deeplearning4j.ui.api.UIServer;
import org.deeplearning4j.ui.stats.StatsListener;
import org.deeplearning4j.ui.storage.InMemoryStatsStorage;
import org.deeplearning4j.zoo.PretrainedType;
import org.deeplearning4j.zoo.model.*;
import org.deeplearning4j.zoo.*;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.dataset.api.preprocessor.DataNormalization;
import org.nd4j.linalg.dataset.api.preprocessor.ImagePreProcessingScaler;
import org.nd4j.linalg.learning.config.IUpdater;
import org.nd4j.linalg.learning.config.RmsProp;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.commands.interfaces.PathCommand;

import javax.lang.model.util.SimpleElementVisitor6;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static java.lang.Math.toIntExact;

/**
 * Created by cschlosser on 23/05/2017.
 * Copied from dl4j example file "Animals Classification" and modified for the dl4j model zoo VGGNetD implementation
 * Trains a CNN from image data
 * Many features need before this becomes useful
 */

//Todo: select model type
//Todo: select which layers to pretrain / train
//Todo: Options for train/test split, augmentation/ hyperparameters, etc.

public class TrainCNNCommand implements PathCommand {


        protected static final Logger log = LoggerFactory.getLogger(TrainCNNCommand.class);
        protected static int height = 224;
        protected static int width = 224;
        protected static int channels = 3;
        protected static int numLabels = 2;
        protected static int batchSize = 32;

        protected static long seed = 42;
        protected static Random rng = new Random(seed);
        protected static int listenerFreq = 1;
        protected static int iterations = 1;
        protected static int epochs = 4;
        protected static double splitTrainTest = 0.8;
        protected static int nCores = 8;
        protected static boolean save = true;

        //private int numLabels;


        public void run()  {

            log.info("Load data....");
            /**cd
             * Data Setup -> organize and limit data file paths:
             *  - mainPath = path to image files
             *  - fileSplit = define basic dataset split with limits on format
             *  - pathFilter = define additional file load filter to limit size and balance batch content
             **/
            ParentPathLabelGenerator labelMaker = new ParentPathLabelGenerator();
            DirectoryChooser dirChooser = new DirectoryChooser();
            File mainPath = dirChooser.showDialog(null );
//            if( !Arrays.asList(mainPath.list()).contains("positive") || !Arrays.asList(mainPath.list()).contains("negative") )
//            {
//                log.error("Training data folder structure is incorrect: must contain  'positive' and 'negative' sub directories");
//                return;
//            }

            FileSplit fileSplit = new FileSplit(mainPath, NativeImageLoader.ALLOWED_FORMATS, rng);
            int numExamples = (int)fileSplit.length();//Math.min((int)fileSplit.length(),5000);
            BalancedPathFilter pathFilter = new BalancedPathFilter(rng,labelMaker,numExamples,numLabels,batchSize);

            String rootParamPath = null;

            /**
             * Data Setup -> train test split
             *  - inputSplit = define train and test split
             **/
            InputSplit[] inputSplit = fileSplit.sample(pathFilter, splitTrainTest, 1 - splitTrainTest);
            InputSplit trainData = inputSplit[0];
            InputSplit testData = inputSplit[1];

            /**
             * Data Setup -> transformation
             *  - Transform = how to tranform images and generate large dataset to train on
             **/
            ImageTransform flipTransform1 = new FlipImageTransform(rng);
            ImageTransform flipTransform2 = new FlipImageTransform(new Random(123));
            ImageTransform warpTransform = new WarpImageTransform(rng, 42);
//          ImageTransform colorTransform = new ColorConversionTransform(new Random(seed), COLOR_BGR2YCrCb);
            List<ImageTransform> transforms = Arrays.asList(new ImageTransform[]{flipTransform1, warpTransform, flipTransform2});

            /**
             * Data Setup -> normalization
             *  - how to normalize images and generate large dataset to train on
             **/
            DataNormalization scaler = new ImagePreProcessingScaler(-1, 1);

            log.info("Build model....");

            IUpdater updater = new RmsProp(0.1, 0.96, 0.001);
            CacheMode cacheMode = CacheMode.DEVICE;
            WorkspaceMode workspaceMode = WorkspaceMode.ENABLED;
            ConvolutionLayer.AlgoMode cudnnAlgoMode = ConvolutionLayer.AlgoMode.PREFER_FASTEST;
            int[] inputShape = new int[] {3, 224, 224};
            ZooModel zooModel = ResNet50.builder().numClasses(2).build();//(seed, inputShape, 2, updater, cacheMode, workspaceMode, cudnnAlgoMode)
            //.cudnnAlgoMode(ConvolutionLayer.AlgoMode.NO_WORKSPACE)
            ComputationGraph pretrainedNet;// = zooModel.init();



            try{pretrainedNet = (ComputationGraph) zooModel.initPretrained(PretrainedType.IMAGENET);}
            catch(java.io.IOException ex){
                log.error("Unable to load pre-trained network.");
                return;
            }


            FineTuneConfiguration fineTuneConf = new FineTuneConfiguration.Builder()
                    .seed(seed)
                    .build();


            ComputationGraph resnetFineTune = new TransferLearning.GraphBuilder(pretrainedNet)
                    .fineTuneConfiguration(fineTuneConf)
                    .setFeatureExtractor("activation_37")
                    .removeVertexKeepConnections("fc1000")
                    .addLayer("fc1000",
                            new OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                                    .nIn(2048).nOut(numLabels)
                                    .weightInit(WeightInit.XAVIER)
                                    .activation(Activation.SOFTMAX).build(),"flatten_1" )
                            //, new CnnToFeedForwardPreProcessor(1,1,2048) , "avg_pool")
//
                    .build();



            resnetFineTune.setListeners(new ScoreIterationListener(listenerFreq));

            /**
             * Data Setup -> define how to load data into net:
             *  - recordReader = the reader that loads and converts image data pass in inputSplit to initialize
             *  - dataIter = a generator that only loads one batch at a time into memory to save memory
             *  - trainIter = uses MultipleEpochsIterator to ensure model runs through the data for all epochs
             **/
            ImageRecordReader recordReader = new ImageRecordReader(height, width, channels, labelMaker);
            DataSetIterator dataIter;
            MultipleEpochsIterator trainIter;

            //Initialize the user interface backend
            UIServer uiServer = UIServer.getInstance();
            log.info(String.format("UI Server: http://localhost:%d", uiServer.getPort()));

            //Configure where the network information (gradients, activations, score vs. time etc) is to be stored
            //Then add the StatsListener to collect this information from the network, as it trains
            StatsStorage statsStorage = new InMemoryStatsStorage();             //Alternative: new FileStatsStorage(File) - see UIStorageExample
            int listenerFrequency = 1;
            resnetFineTune.setListeners(new StatsListener(statsStorage, listenerFrequency));

            //Attach the StatsStorage instance to the UI: this allows the contents of the StatsStorage to be visualized
            uiServer.attach(statsStorage);

            log.info("Train model....");
            // Train without transformations
            try {recordReader.initialize(trainData, null);}
            catch(IOException ex)
            {
             log.error("Error initializing record reader on training data");
            }
            dataIter = new RecordReaderDataSetIterator(recordReader, batchSize, 1, numLabels);
            scaler.fit(dataIter);
            dataIter.setPreProcessor(scaler);
            trainIter = new MultipleEpochsIterator( epochs, dataIter, nCores);
            resnetFineTune.fit(dataIter,epochs);

             //Train with transformations
            for (ImageTransform transform : transforms) {
                System.out.print("\nTraining on transformation: " + transform.getClass().toString() + "\n\n");
                try{recordReader.initialize(trainData, transform);}
                catch(IOException ex)
                {
                    log.error("Error initializing record reader on augmented data");
                }
                dataIter = new RecordReaderDataSetIterator(recordReader, batchSize, 1, numLabels);
                scaler.fit(dataIter);
                dataIter.setPreProcessor(scaler);
                trainIter = new MultipleEpochsIterator(epochs, dataIter, nCores);
                resnetFineTune.fit(trainIter);
            }

            log.info("Evaluate model....");
            try{recordReader.initialize(testData);}
            catch(IOException ex)
            {
                log.error("Error initializing record reader on test data");
            }
            dataIter = new RecordReaderDataSetIterator(recordReader, batchSize, 1, numLabels);
            scaler.fit(dataIter);
            dataIter.setPreProcessor(scaler);
            Evaluation eval = resnetFineTune.evaluate(dataIter);
            log.info(eval.stats(true));

            // Example on how to get predict results with trained model
//            dataIter.reset();
//            DataSet testDataSet = dataIter.next();
//            String expectedResult = testDataSet.getLabelName(0);
//            INDArray predict = resnetFineTune.outputSingle(testDataSet.getFeatureMatrix());
//            String modelResult = predict.getDouble(0)>0.5 ? labelMaker.ge:"normal" ;
//            System.out.print("\nFor a single example that is labeled " + expectedResult + " the model predicted " + modelResult + "\n\n");

            if (save) {
                log.info("Save model....");
                String basePath = FilenameUtils.concat(System.getProperty("user.dir"), "src/main/resources/");
                try{ModelSerializer.writeModel(resnetFineTune, basePath + "model.bin", true);}
                catch(IOException ex)
                {
                    log.error("Error writing model");
                }
            }
            log.info("****************Example finished********************");
        }


   //     public static void main(String[] args) throws Exception {
    //        new TrainCNNCommand().run();
   //     }

    }



