package com.panda.lns.scratch;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.util.SparseLongArray;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.concurrent.ScheduledExecutorService;

public class SensorService extends Service implements SensorEventListener {
    public static boolean storeData=false;
    public static boolean run=false;


    private static final String TAG = "SensorService";
    private final static int SENS_GYROSCOPE = Sensor.TYPE_GYROSCOPE;
    private final static int SENS_LINEAR_ACCELERATION = Sensor.TYPE_LINEAR_ACCELERATION;



    //recording stuff
    public static final String DATA_FILE_NAME = "data.txt";
    private MediaPlayer mMediaPlayer;
    private static final int RECORDING_RATE = 8000; // can go up to 44K, if needed
    private static final int CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO;
    private static final int FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static int BUFFER_SIZE = AudioRecord.getMinBufferSize(RECORDING_RATE, CHANNEL_IN, FORMAT)*16;//2 seconds approx

    private State mState = State.IDLE;

    private AsyncTask<Void, Void, Void> mRecordingAsyncTask;

    enum State {
        IDLE, RECORDING
    }



    SensorManager mSensorManager;
    private static DeviceClient client;
    private ScheduledExecutorService mScheduler;

    private AsyncTask<Void, Void, Void> sensorFileWriteTask;
    Context context;
    private SparseLongArray lastSensorData;

    private static float calibX=0.0f;
    private static float calibY=0.0f;
    private static float calibZ=0.0f;
    private  static float lastX=0.0f;
    private static float lastY=0.0f;
    private static float lastZ=0.0f;


    private static boolean record=false;

    @Override
    public void onCreate() {
        super.onCreate();

        client = DeviceClient.getInstance(this);

        Notification.Builder builder = new Notification.Builder(this);
        builder.setContentTitle("Sensor Dashboard");
        builder.setContentText("Collecting sensor data..");

        startForeground(1, builder.build());
        run=true;
        startMeasurement();
        context=this;
        lastSensorData = new SparseLongArray();
        soundSample();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        run=false;
        dataList.clear();
        stopMeasurement();
        Log.w(TAG, "Stopping measurement");

    }


    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    protected void startMeasurement() {
        isFirstTime=true;
        strokes=0;
        Log.w("haha", "started!");
        mSensorManager = ((SensorManager) getSystemService(SENSOR_SERVICE));
        Sensor gyroscopeSensor = mSensorManager.getDefaultSensor(SENS_GYROSCOPE);
        Sensor linearAccelerationSensor = mSensorManager.getDefaultSensor(SENS_LINEAR_ACCELERATION);

        // Register the listener
        if (mSensorManager != null) {
            if (gyroscopeSensor != null) {
                mSensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_FASTEST);
            } else {
                Log.w(TAG, "No Gyroscope Sensor found");
            }
            if (linearAccelerationSensor != null) {
                mSensorManager.registerListener(this, linearAccelerationSensor, SensorManager.SENSOR_DELAY_FASTEST);
            } else {
                Log.d(TAG, "No Linear Acceleration Sensor found");
            }
        }
    }
    void showMessage(String s){
        Message msg = Message.obtain();
        msg.obj = new String(s);
        wearActivity.txtLogHandler.sendMessage(msg) ;
    }


    public void soundSample() {
        if (mState != State.IDLE) {
            Log.w(TAG, "Requesting to start recording while state was not IDLE");
        }

        mRecordingAsyncTask = new AsyncTask<Void, Void, Void>() {

            short[] buffer = new short[BUFFER_SIZE];
            AudioRecord mAudioRecord=null;
            ArrayList<String> list = new ArrayList<String>();
            long ts;
            final int sample = 256;


            void showMessage(String s){
                Message msg = Message.obtain();
                msg.obj = new String(s);
                wearActivity.txtLogHandler.sendMessage(msg) ;
            }

            @Override
            protected void onPreExecute() {
                mAudioRecord=new AudioRecord(MediaRecorder.AudioSource.MIC, RECORDING_RATE, CHANNEL_IN, FORMAT, BUFFER_SIZE);
                mState = State.RECORDING;
                mAudioRecord.startRecording();
                initClassifier();

            }
            ArrayList<String> l = new ArrayList<String>();

            @Override
            protected Void doInBackground(Void... params) {
                Log.w(TAG, "sound samlpe!");
                int number=0;
                while (run){
                    strokes=0;
                    zCrossingTs.clear();
                    Log.w(TAG, "start!!!!!!");
                    getSample();
                   // l.clear();
                    for(int i=0;i<buffer.length;i++){
                        //l.add(Short.toString(buffer[i]));
                    }
                   // writeFile(l);
                    Log.w(TAG, "vlist size = " + vList.size());

                    //find the index where the time difference is 4 seconds
                    int n=0;
                    for(int i=vList.size()-1;i>=0;i--){
                        if((((vList.get(vList.size()-1).ts - vList.get(i).ts) * 0.000001)) > 2000){
                            n=i;
                            break;
                        }
                    }
                    n+=1;
                    if((vList.size() - n) > 10){
                        Log.w(TAG, "in you!!");
                        double[] vAr = new double[64];
                        int [] numInputs = new int[vAr.length];
                        for(int i=0;i<numInputs.length;i++){
                            numInputs[i] =0;
                        }
                        for(int i=0;i<vAr.length;i++){
                            vAr[i] = 0;
                        }
                        for(int i=n;i<vList.size();i++){
                            int index = (int) Math.round(((vList.get(vList.size() - 1).ts - vList.get(i).ts))*63*0.000000001);
                            if((index >=0)&&(index < 64)){
                                vAr[index] +=vList.get(i).v;
                                numInputs[index]++;
                            }
                        }
                        for(int i =0;i<vAr.length;i++){
                            if(numInputs[i] > 0){
                                vAr[i]/=numInputs[i];
                            }else{
                                vAr[i] = -1;
                            }
                        }

                        //filling the missing values
                        for(int i=0;i<vAr.length;i++){
                            if(vAr[i]==-1){
                                if(i!=0){
                                    vAr[i]=vAr[i-1];
                                }else{
                                    vAr[i]=vAr[i+1];
                                }
                            }
                        }

                        //calculate FFT of audio data
                        double[] soundArray = getDoubleArray(buffer);
                        ArrayList<Double> fftList = takeSampleFFT(soundArray, sample, 70, 75);//initial : 22 to 57
                        //record v and sound FFT


                        final int sampleLength=32;
                        final int step=5;
                        double[] vArray=new double[sampleLength];
                        double[] sArray = new double[sampleLength];

                        int count=0;
                        int score=0;
                        double similarity=0;
                        int rub=0;
                        int nonRub=0;
                        for(int i=0; i<vAr.length;i++){
                            vArray[count%sampleLength] = (Math.abs(vAr[i]));
                            sArray[count%sampleLength] = fftList.get(i);
                            count++;
                            if((count)%sampleLength == 0){
                                double[] vfft = getEnergies(vArray);
                                double[] sfft = getEnergies(sArray);
                                double tot=0;
                                for(int k=1;k<vfft.length/2;k++){
                                    tot+=(sfft[k]);
                                }
                                StrokeData data = new StrokeData(vfft[0],vfft[1],vfft[2],vfft[3],vfft[4],vfft[5],vfft[6],vfft[7],vfft[8],vfft[9],vfft[10],vfft[11],vfft[12],vfft[13],vfft[14],vfft[15],sfft[0],sfft[1],sfft[2],sfft[3],sfft[4],sfft[5],sfft[6],sfft[7],sfft[8],sfft[9],sfft[10],sfft[11],sfft[12],sfft[13],sfft[14],sfft[15]);
                                score=classifyh01RN(data);
                                if(score ==0){
                                    rub++;
                                }else{
                                    nonRub++;
                                }
                                //Log.w(TAG, v + "," + s + "R");
                                i-=(sampleLength-step);
                                count=0;
                            }
                        }
                        number++;
                        if(rub > nonRub){
                            client.sendString("Rubbing");
                        }else if(nonRub>rub){
                            client.sendString("Not Rubbing");
                        }else{
                            client.sendString("Cannot detect");
                        }
                    }
                }
                mAudioRecord.stop();
                return null;
            }
            int classifyh01RN(StrokeData sd){
                double[] values = new double[7];
                values[0] =sd.v1;
                values[1] =sd.v3;
                values[2] =sd.v6;
                values[3] =sd.v7;
                values[4] =sd.v8;
                values[5] = sd.v13;
                values[6] = sd.s1;
                return (nb.getPrediction(values));
            }
            DiscNB nb=null;
            void initClassifier(){
                DiscNBPredictor[] predictorhR = new DiscNBPredictor[]{new DiscNBPredictor(7, new double[]{33.87,37.82,45.27,63.08,79.79,93.53}, new double[]{0.005,0.025,0.126,0.393,0.385,0.065,0.002}) , new DiscNBPredictor(3, new double[]{13.45,17.15}, new double[]{0.977,0.021,0.002}), new DiscNBPredictor(2, new double[]{3.34}, new double[]{0.277,0.723}), new DiscNBPredictor(2, new double[]{3.163}, new double[]{0.33,0.67}) , new DiscNBPredictor(3, new double[]{2.320,10.247}, new double[]{0.282,0.678,0.041}) , new DiscNBPredictor(3, new double[]{2.1124,4.6623}, new double[]{0.41,0.428,0.162}) , new DiscNBPredictor(5, new double[]{374.728,746.5,863.55,1042.49,1224.13}, new double[]{0.001,0.608,0.136,0.153,0.005,0.097})};
                DiscNBPredictor[] predictorhN = new DiscNBPredictor[]{new DiscNBPredictor(7, new double[]{33.87,37.82,45.27,63.08,79.79,93.53}, new double[]{0.22,0.058,0.102,0.036,0.141,0.247,0.197}) , new DiscNBPredictor(3, new double[]{13.45,17.15}, new double[]{0.752,0.088,0.16}), new DiscNBPredictor(2, new double[]{3.34}, new double[]{0.511,0.489}), new DiscNBPredictor(2, new double[]{3.163}, new double[]{0.571,0.429}) , new DiscNBPredictor(3, new double[]{2.320,10.247}, new double[]{0.495,0.502,0.002}) , new DiscNBPredictor(3, new double[]{2.1124,4.6623}, new double[]{0.639,0.323,0.038}) , new DiscNBPredictor(5, new double[]{374.728,746.5,863.55,1042.49,1224.13}, new double[]{0.083,0.267,0.291,0.065,0.106,0.188})};
                DiscNBClass R = new DiscNBClass("R" ,0.5,predictorhR);
                DiscNBClass N = new DiscNBClass("N" ,0.5,predictorhN);
                DiscNBClass[] classes = new DiscNBClass[]{R,N};
                nb= new DiscNB(classes);

            }
            double stdev(double[] ar){
                double avg=0;
                for(int i=0;i<ar.length;i++){
                    avg+=ar[i];
                }
                avg/=ar.length;
                double stdev=0;
                for(int i=0;i<ar.length;i++){
                    stdev+=Math.abs(ar[i] - avg);
                }
                stdev/=ar.length;
                return stdev;
            }

            double[] normalise(double[] ar){
                double min,max;
                double avg=0;
                min=ar[0];
                max=ar[0];
                for(int i=1;i<ar.length;i++){
                    if(ar[i] > max){
                        max=ar[i];
                    }
                    if(ar[i] < min){
                        min=ar[i];
                    }
                    avg+=ar[i];
                }
                avg/=ar.length;

                for(int i=0;i<ar.length;i++){
                    ar[i] = (ar[i] - avg)/(max-min);
                }
                return ar;
            }


            double getError(double[] ar1, double[] ar2){
                ar1 = normalise(ar1);
                ar2=normalise(ar2);
                double error=0;
                for(int i=0;i<ar1.length;i++){
                    error+=Math.abs(ar1[i] - ar2[i]);
                }
                return error/ar1.length;
            }

            double getOneRatio(double[] ar1, double[] ar2){
                double minSum1=0;
                double maxSum1=0;
                double minSum2=0;
                double maxSum2=0;
                for(int i=0;i<ar1.length;i++){
                    if(ar1[i] < 0){
                        minSum1+=ar1[i];
                    }
                    if(ar1[i] > 0) {
                        maxSum1+=ar1[i];
                    }
                }
                for(int i=0;i<ar2.length;i++){
                    if(ar1[i] < 0){
                        minSum2+=ar2[i];
                    }
                    if(ar1[i] > 0) {
                        maxSum2+=ar2[i];
                    }
                }
                return (Math.abs(Math.abs(maxSum1)/Math.abs(minSum1) - Math.abs(maxSum2)/Math.abs(minSum2)));
            }


            double getCorrelation(double[] ar1,double ar2[]){
                double cor=0;
                for(int i=0;i<ar1.length;i++){
                    cor+=(ar1[i] * ar2[i]);
                }
                return cor/(stdev(ar1) * stdev(ar2)*ar1.length);
            }

            double[] getShiftedArray(double[] ar, int shift, double[] arBefore, double[] arAfter){
                //negative shift << ; positive shift >>
                if(arBefore==null){
                    arBefore=new double[ar.length];
                    for(int i=0;i<ar.length;i++){
                        arBefore[i] =0;
                    }
                }
                if(arAfter==null){
                    arAfter=new double[ar.length];
                    for(int i=0;i<ar.length;i++){
                        arAfter[i] =0;
                    }
                }
                double[] newAr = new double[ar.length];

                if(shift<0){
                    for(int i=0;i<newAr.length+shift;i++){
                        newAr[i] = ar[i-shift];
                    }
                    for(int i=newAr.length+shift;i<newAr.length;i++){
                        newAr[i] = arAfter[i-(newAr.length+shift)];
                    }
                }else{
                    for(int i=shift;i<newAr.length;i++){
                        newAr[i] = ar[i-shift];
                    }
                    for(int i=0;i<shift;i++){
                        newAr[i] = arBefore[arBefore.length-shift+i];
                    }
                }
                return newAr;

            }

            void getSample() {
                mState = State.RECORDING;
                Log.e(TAG, "State = " + mAudioRecord.getRecordingState()) ;
                int read = mAudioRecord.read(buffer, 0, buffer.length,AudioRecord.READ_BLOCKING);
                Log.w("buffer length  = ", Integer.toString(buffer.length));
                Log.w("sound size = ", Integer.toString(read));
                 mState = State.IDLE;
            }

            double getEnergy(double[] array, int a, int b){ //a=22 b=57 for good energy
                Complex[] compAr = new Complex[array.length];
                for(int i=0;i<array.length;i++){
                    compAr[i] = new Complex(array[i], 0.0);
                }
                Complex[] fftAr = new Complex[array.length];
                fftAr = FFT.fft(compAr);
                double goodEnergy=0.0;
                for(int i=a;i<b;i++){
                    goodEnergy += fftAr[i].abs();
                }

                return (goodEnergy);
            }

             double[] getEnergies(double[] array){
                Complex[] compAr = new Complex[array.length];
                for(int i=0;i<array.length;i++){
                    compAr[i] = new Complex(array[i], 0.0);
                }
                Complex[] fftAr = new Complex[array.length];
                fftAr = FFT.fft(compAr);
                double[] retAr = new double[array.length];
                for(int i=0;i<retAr.length;i++){
                    retAr[i] = fftAr[i].abs();
                }
                return retAr;
            }

             ArrayList<Double> takeSampleFFT(double[] ar, int step, int a , int b){
                ArrayList<Double> list = new ArrayList<Double>();
                double[] tmp = new double[sample];
                 double sum=0;
                for(int i=0;i<=ar.length-sample;i+=step){
                    for(int j=0;j<sample;j++){
                        tmp[j] = ar[i+j];
                    }
                    list.add((getSpectralCover(tmp, a, b))/getEnergy(tmp,a,b));//initial 22 to 57
                }
                return list;
            }
            double getSpectralCover(double[] array, int a, int b){
                double num=0;
                double den =0;
                Complex[] compAr = new Complex[array.length];
                for(int i=0;i<array.length;i++){
                    compAr[i] = new Complex(array[i], 0.0);
                }
                Complex[] fftAr = new Complex[array.length];
                fftAr = FFT.fft(compAr);
                if((a==0)&&(b==0)){
                    for(int i=0;i<fftAr.length/2-5;i++){
                        num+=Math.pow((fftAr[i].abs() * i), 2.0);
                        den+=fftAr[i].abs();
                    }
                    den = Math.pow(den,1.5);
                }else{
                    for(int i=a;i<b;i++){
                        num+=Math.pow((fftAr[i].abs() * i), 2.0);
                        den+=fftAr[i].abs();
                    }
                    den = Math.pow(den,1.5);
                }

                return num/den;


            }

            double[] getDoubleArray(short[] array){
                double[] dArray = new double[array.length];
                for(int i=0;i<array.length;i++){
                    dArray[i] = array[i];
                }
                return dArray;
            }



            @Override
            protected void onPostExecute(Void aVoid) {
                mState = State.IDLE;
                mAudioRecord.stop();
                mRecordingAsyncTask = null;
            }

            @Override
            protected void onCancelled() {
                if (mState == State.RECORDING) {
                    Log.d(TAG, "Stopping the recording ...");
                    mState = State.IDLE;
                } else {
                    Log.w(TAG, "Requesting to stop recording while state was not RECORDING");
                }
                mRecordingAsyncTask = null;
            }
        };

        mRecordingAsyncTask.execute();
    }

    public static void calibrate(){
        calibX= lastX;
        calibY= lastY;
        calibZ= lastZ;
        client.sendString("Calibrated : " + calibX +" " + calibY + " " + calibZ );

    }

    private void stopMeasurement() {
        if (mSensorManager != null) {
            mSensorManager.unregisterListener(this);
        }
    }

    static boolean isFirstTime=true;
    long ts, lastTs;
    long tsEvent;
    static int accCount=0;
    float xSum=0;
    float ySum=0;
    float zSum=0;
    float xv=0;
    float yv=0;
    float zv=0;
    float xDist=0;
    float yDist=0;
    float zDist=0;
    float xwDist=0;
    float ywDist=0;
    float zwDist=0;
    int n=0;
    float avgAccXY=0;
    float lastV=0;
    float gyrSum=0;
    float lastGyrSum=0;
    float v=0;
    float dist=0;
    int gyrCount=0;
    int strokes=0;
    long[] last2ts = new long[2];
    boolean storeV=false;
    ArrayList<Long> accTsList = new ArrayList<Long>();
    ArrayList<Long> vTsList = new ArrayList<Long>();
    ArrayList<Long> zCrossingTs = new ArrayList<Long>();


    static boolean isStore=false;
    LimitedArrayList vList = new LimitedArrayList(300);
    ArrayList<StrokeData> dataList = new ArrayList<StrokeData>();

    @Override
    public void onSensorChanged(SensorEvent event) {
        long lastTimestamp = lastSensorData.get(event.sensor.getType());
        long timeAgo = event.timestamp - lastTimestamp; // in nano seconds

        if (lastTimestamp != 0) {
            if (timeAgo < 100) { //1 ms
                return;
            }
            if (!((event.sensor.getType()==10) || (event.sensor.getType() == 4))) {
                return;
            }
        }
        lastSensorData.put(event.sensor.getType(), event.timestamp);

        float x=0;
        float y=0;
        float z=0;
        float xGyr,yGyr,zGyr;

        if(event.sensor.getType()==10){
            accCount++;
            x=event.values[0]-calibX;
            y=event.values[1]-calibY;
            z=event.values[2]-calibZ;
            lastX=x;
            lastY=y;
            lastZ=z;
            xSum+=x;
            ySum+=y;
            zSum+=z;
            if(accCount==2){
                //ts = event.timestamp;
            }
            if(accCount >= 4){
                xSum/=accCount;
                ySum/=accCount;
                zSum/=accCount;
                xv+=xSum;
                yv+=ySum;
                zv+=zSum;
                //vList.add(new DataPoint(ts, (xv+yv+zv)));
                //Log.w(TAG, "xv = " + xv);
                lastV = (xv + yv);
                dist+=Math.abs(xv+yv);
                xDist+=Math.abs(xv);
                yDist+=Math.abs(yv);
                zDist+=Math.abs(zv);
                n++;
                accCount=0;
                lastTs=ts;
                xSum=0;
                ySum=0;
            }

        }else{  // if its gyro
            gyrCount++;
            xGyr=event.values[0];
            yGyr=event.values[1];
            zGyr=event.values[2];

            if(xGyr*yGyr<0) yGyr*=(-1);
            if(xGyr*zGyr<0) zGyr*=(-1);
            gyrSum+= xGyr+yGyr+zGyr;
            if(gyrCount == 2){
                ts=event.timestamp;
            }
            if(gyrCount >= 4){
                vList.add(new DataPoint(ts,gyrSum/gyrCount));
                if((lastGyrSum*gyrSum)<0){
                    xv=0;
                    yv=0;
                    zv=0;
                    xDist=0;
                    yDist=0;
                    zDist=0;
                    xwDist=0;
                    ywDist=0;
                    zwDist=0;
                    dist=0;
                    n=0;
                }
                lastGyrSum=gyrSum/gyrCount;
                gyrSum=0;
                gyrCount=0;
            }
        }
    }



    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }
}

class StrokeData{
    double v1,v2,v3,v4,v5,v6,v7,v8,v9,v10,v11,v12,v13,v14,v15,v16,s1,s2,s3,s4,s5,s6,s7,s8,s9,s10,s11,s12,s13,s14,s15,s16;
    StrokeData(double v1, double v2, double v3, double v4,double v5, double v6, double v7, double v8,double v9, double v10, double v11, double v12,double v13, double v14, double v15, double v16, double s1, double s2, double s3, double s4,double s5, double s6, double s7, double s8,double s9, double s10, double s11, double s12,double s13, double s14, double s15, double s16){
        this.v1=v1;
        this.v2=v2;
        this.v3=v3;
        this.v4=v4;
        this.v5=v5;
        this.v6=v6;
        this.v7=v7;
        this.v8=v8;
        this.v9=v9;
        this.v10=v10;
        this.v11=v11;
        this.v12=v12;
        this.v13=v13;
        this.v14=v14;
        this.v15=v15;
        this.v16=v16;
        this.s1=s1;
        this.s2=s2;
        this.s3=s3;
        this.s4=s4;
        this.s5=s5;
        this.s6=s6;
        this.s7=s7;
        this.s8=s8;
        this.s9=s9;
        this.s10=s10;
        this.s11=s11;
        this.s12=s12;
        this.s13=s13;
        this.s14=s14;
        this.s15=s15;
        this.s16=s16;
    }
}

class DataPoint{
    long ts;
    float v;

    DataPoint(long ts, float v){
        this.ts=ts;
        this.v=v;
    }

}


class LimitedArrayList extends ArrayList<DataPoint>{
    private int limit=0;
    LimitedArrayList(int limit){
        this.limit=limit;
    }
    public boolean add(DataPoint sp){
        if(this.size() < limit){
            return super.add(sp);
        }else{
            super.remove(0);
            return super.add(sp);
        }
    }
}







