package polar.com.alpha1;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
//import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

// https://github.com/jjoe64/GraphView
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.ejml.simple.SimpleMatrix;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.functions.Function;
import polar.com.sdk.api.PolarBleApi;
import polar.com.sdk.api.PolarBleApiCallback;
import polar.com.sdk.api.PolarBleApiDefaultImpl;
import polar.com.sdk.api.errors.PolarInvalidArgument;
import polar.com.sdk.api.model.PolarDeviceInfo;
import polar.com.sdk.api.model.PolarHrData;

import static java.lang.Math.abs;
import static java.lang.Math.pow;
import static java.lang.Math.round;
import static java.lang.Math.sqrt;

public class MainActivity extends AppCompatActivity {
    public static final boolean requestLegacyExternalStorage = true;
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String API_LOGGER_TAG = "FatMaxxer";
    public static final String AUDIO_OUTPUT_ENABLED = "audioOutputEnabled";
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_TAG = "alpha1update";

    final double alpha1HRVvt1 = 0.75;
    final double alpha1HRVvt2 = 0.5;

    // FIXME: Catch UncaughtException
    // https://stackoverflow.com/questions/19897628/need-to-handle-uncaught-exception-and-send-log-file

    public MainActivity() {
        //super(R.layout.activity_fragment_container);
        super(R.layout.activity_main);
        Log = new Log();
    }



    public void deleteFile(Uri uri) {
        File fdelete = new File(uri.getPath());
        if (fdelete.exists()) {
            if (fdelete.delete()) {
                System.out.println("file Deleted :" + uri.getPath());
            } else {
                System.out.println("file not Deleted :" + uri.getPath());
            }
        }
    }

    @Override
    public void finish() {
        closeLogs();
        uiNotificationManager.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
        try {
            api.disconnectFromDevice(DEVICE_ID);
        } catch (PolarInvalidArgument polarInvalidArgument) {
            Log.d(TAG, "Quit: disconnectFromDevice: polarInvalidArgument "+
                    polarInvalidArgument.getStackTrace());
        }
        super.finish();
    }

    private long pressedTime;
    public void onBackPressed() {
        if (pressedTime + 2000 > System.currentTimeMillis()) {
            super.onBackPressed();
            finish();
        } else {
            Toast.makeText(getBaseContext(), "Press back again to exit", Toast.LENGTH_SHORT).show();
        }
        pressedTime = System.currentTimeMillis();
    }

    private final double[] samples1 = {667.0,674.0,688.0,704.0,690.0,688.0,671.0,652.0,644.0,636.0,631.0,639.0,637.0,634.0,642.0,642.0,
            653.0,718.0,765.0,758.0,729.0,713.0,691.0,677.0,694.0,695.0,692.0,684.0,685.0,677.0,667.0,657.0,648.0,632.0,
            652.0,641.0,644.0,665.0,711.0,753.0,772.0,804.0,844.0,842.0,833.0,818.0,793.0,781.0,799.0,822.0,820.0,835.0,
            799.0,793.0,745.0,764.0,754.0,764.0,768.0,764.0,770.0,766.0,765.0,777.0,767.0,756.0,724.0,747.0,812.0,893.0,
            905.0,924.0,945.0,946.0,897.0,857.0,822.0,571.0,947.0,770.0,794.0,840.0,805.0,1593.0,763.0,1498.0,735.0,
            745.0,742.0,737.0,748.0,756.0,756.0,762.0,783.0,814.0,826.0,838.0,865.0,877.0,859.0,858.0,855.0,861.0,870.0,
            902.0,902.0,879.0,847.0,835.0,847.0,884.0,940.0,971.0,936.0,896.0,873.0,879.0,888.0,896.0,904.0,902.0,901.0,899.0,
            893.0,914.0,997.0,966.0,902.0,899.0,909.0,933.0,954.0,947.0,892.0,830.0,825.0,813.0,790.0,759.0,744.0,739.0,
            724.0,699.0,1401.0,694.0,684.0,683.0,696.0,710.0,738.0};
    private final double samples1_hr = 80;
    private final double samples1_alpha1 = 0.86;
    private final double samples1_rmssd = 158;

    PolarBleApi api;
    Disposable broadcastDisposable;
    Disposable ecgDisposable;
    Disposable accDisposable;
    Disposable gyrDisposable;
    Disposable magDisposable;
    Disposable ppgDisposable;
    Disposable ppiDisposable;
    Disposable scanDisposable;
    Disposable autoConnectDisposable;
    // Serial number?? 90E2D72B
    String DEVICE_ID = "";
    SharedPreferences sharedPreferences;

    Notification initialNotification;

    Context thisContext = this;
    private int batteryLevel = 0;
    private String exerciseMode = "Light";
    private EditText input_field;
    private static final String SERVICE_CHANNEL_ID = "FatMaxxerServiceChannel";
    private static final String UI_CHANNEL_ID = "FatMaxxerUIChannel";
    private static final String SERVICE_CHANNEL_NAME = "FatMaxxer Service Notification";
    private static final String UI_CHANNEL_NAME = "FatMaxxer Notifications";

    public static class LocalService extends Service {
        private NotificationManager mNM;

        @Override
        public void onDestroy() {
            Log.d(TAG, "LocalService: onDestroy");
            super.onDestroy();
            //mNM.cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
        }

        public LocalService() {
        }

        /**
         * Class for clients to access.  Because we know this service always
         * runs in the same process as its clients, we don't need to deal with
         * IPC.
         */
        public class LocalBinder extends Binder {
            LocalService getService() {
                return LocalService.this;
            }
        }


        //https://stackoverflow.com/questions/47531742/startforeground-fail-after-upgrade-to-android-8-1
        @Override
        public void onCreate() {
            Log.d(TAG,"FatMaxxer service onCreate");
            super.onCreate();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startMyOwnForeground();
            else
                startForeground(1, new Notification());
        }

        private void startMyOwnForeground(){
//            NotificationChannel chan = new NotificationChannel(SERVICE_CHANNEL_ID, SERVICE_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
//            chan.setLightColor(Color.BLUE);
//            chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
//            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
//            assert manager != null;
//            manager.createNotificationChannel(chan);

            // https://stackoverflow.com/questions/5502427/resume-application-and-stack-from-notification
            final Intent notificationIntent = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // https://stackoverflow.com/a/42002705/16251655
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
            //notificationBuilder.setContentIntent(pendingIntent)

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, SERVICE_CHANNEL_ID);
            Notification notification =
                    notificationBuilder.setOngoing(true)
                    .setSmallIcon(R.mipmap.ic_launcher_foreground)
                    .setContentTitle("FatMaxxer service started")
                    .setPriority(NotificationManager.IMPORTANCE_HIGH)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setContentIntent(pendingIntent)
                    .build();

            //notification.notify(NOTIFICATION_TAG, NOTIFICATION_ID, );
            startForeground(2, notification);
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            Log.d("FatMaxxerLocalService", "Received start id " + startId + ": " + intent);
            return START_NOT_STICKY;
        }

        @Override
        public IBinder onBind(Intent intent) {
            return mBinder;
        }

        // This is the object that receives interactions from clients.  See
        // RemoteService for a more complete example.
        private final IBinder mBinder = new LocalBinder();

        //@RequiresApi(Build.VERSION_CODES.O)
        private String createNotificationChannel() {
            NotificationChannel chan = new NotificationChannel(SERVICE_CHANNEL_ID,
                    SERVICE_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
//            chan.lightColor = Color.BLUE;
//            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE;
            NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            service.createNotificationChannel(chan);
            return SERVICE_CHANNEL_ID;
        }
    }

    /**
     * Example of binding and unbinding to the local service.
     * bind to, receiving an object through which it can communicate with the service.
     *
     * Note that this is implemented as an inner class only keep the sample
     * all together; typically this code would appear in some separate class.
     */
//    public static class Binding extends Activity {

        //
        // SERVICE BINDING
        //

        // Don't attempt to unbind from the service unless the client has received some
        // information about the service's state.
        private boolean mShouldUnbind;

        LocalService service;

    // To invoke the bound service, first make sure that this value
        // is not null.
        private LocalService mBoundService;

        private ServiceConnection mConnection = new ServiceConnection() {
            public void onServiceConnected(ComponentName className, IBinder service) {
                // This is called when the connection with the service has been
                // established, giving us the service object we can use to
                // interact with the service.  Because we have bound to a explicit
                // service that we know is running in our own process, we can
                // cast its IBinder to a concrete class and directly access it.
                mBoundService = ((LocalService.LocalBinder) service).getService();

                // Tell the user about this for our demo.
                Toast.makeText(MainActivity.this, "FatMaxxer bound to service",
                        Toast.LENGTH_SHORT).show();
            }

            public void onServiceDisconnected(ComponentName className) {
                // This is called when the connection with the service has been
                // unexpectedly disconnected -- that is, its process crashed.
                // Because it is running in our same process, we should never
                // see this happen.
                mBoundService = null;
                Toast.makeText(MainActivity.this, "FatMaxxer disconnected from service",
                        Toast.LENGTH_SHORT).show();
            }
        };

        void doBindService() {
            Log.d(TAG,"FatMaxxer: binding to service");
            // Attempts to establish a connection with the service.  We use an
            // explicit class name because we want a specific service
            // implementation that we know will be running in our own process
            // (and thus won't be supporting component replacement by other
            // applications).
            if (bindService(new Intent(MainActivity.this, LocalService.class),
                    mConnection, Context.BIND_AUTO_CREATE)) {
                mShouldUnbind = true;
            } else {
                Log.e(TAG, "Error: The requested service doesn't " +
                        "exist, or this client isn't allowed access to it.");
            }
        }

        void doUnbindService() {
            if (mShouldUnbind) {
                // Release information about the service's state.
                unbindService(mConnection);
                mShouldUnbind = false;
            }
        }

        //
        // END BINDING
        //

    public static class MySettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
        }
    }

    // https://www.bragitoff.com/2017/04/polynomial-fitting-java-codeprogram-works-android-well/
            /*  int n;                       //degree of polynomial to fit the data
                double[] x=double[];         //array to store x-axis data points
                double[] y=double[];         //array to store y-axis data points
            */
    public double[] polyFit(double[] x, double[] y, int degree) {
        //Log.d(TAG, "polyFit x.length "+x.length+" y.length "+y.length+" degree "+degree);
        int n = degree;
        int length = x.length;
        // ASSERT: x.length == y.length
        double X[] = new double[2 * n + 1];
        for (int i = 0; i < 2 * n + 1; i++) {
            X[i] = 0;
            for (int j = 0; j < length; j++) {
                X[i] = X[i] + pow(x[j], i);        //consecutive positions of the array will store length,sigma(xi),sigma(xi^2),sigma(xi^3)....sigma(xi^2n)
            }
        }
        double[][] B = new double[n + 1][n + 2];            //B is the Normal matrix(augmented) that will store the equations, 'a' is for value of the final coefficients
        double[] a = new double[n + 1];
        for (int i = 0; i <= n; i++)
            for (int j = 0; j <= n; j++)
                B[i][j] = X[i + j];            //Build the Normal matrix by storing the corresponding coefficients at the right positions except the last column of the matrix
        double Y[] = new double[n + 1];                    //Array to store the values of sigma(yi),sigma(xi*yi),sigma(xi^2*yi)...sigma(xi^n*yi)
        for (int i = 0; i < n + 1; i++) {
            Y[i] = 0;
            for (int j = 0; j < length; j++)
                Y[i] = Y[i] + pow(x[j], i) * y[j];        //consecutive positions will store sigma(yi),sigma(xi*yi),sigma(xi^2*yi)...sigma(xi^n*yi)
        }
        for (int i = 0; i <= n; i++)
            B[i][n + 1] = Y[i];                //load the values of Y as the last column of B(Normal Matrix but augmented)
        n = n + 1;
        for (int i = 0; i < n; i++)                    //From now Gaussian Elimination starts(can be ignored) to solve the set of linear equations (Pivotisation)
            for (int k = i + 1; k < n; k++)
                if (B[i][i] < B[k][i])
                    for (int j = 0; j <= n; j++) {
                        double temp = B[i][j];
                        B[i][j] = B[k][j];
                        B[k][j] = temp;
                    }
        for (int i = 0; i < n - 1; i++)            //loop to perform the gauss elimination
            for (int k = i + 1; k < n; k++) {
                double t = B[k][i] / B[i][i];
                for (int j = 0; j <= n; j++)
                    B[k][j] = B[k][j] - t * B[i][j];    //make the elements below the pivot elements equal to zero or elimnate the variables
            }
        for (int i = n - 1; i >= 0; i--)                //back-substitution
        {                        //x is an array whose values correspond to the values of x,y,z..
            a[i] = B[i][n];                //make the variable to be calculated equal to the rhs of the last equation
            for (int j = 0; j < n; j++)
                if (j != i)            //then subtract all the lhs values except the coefficient of the variable whose value                                   is being calculated
                    a[i] = a[i] - B[i][j] * a[j];
            a[i] = a[i] / B[i][i];            //now finally divide the rhs by the coefficient of the variable to be calculated
        }
        return a;
    }
    public double[] v_reverse(double[] x) {
        double[] result = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            result[x.length - i - 1] = x[i];
        }
        return result;
    }
    private boolean v_contains(double[] x, Function<Double,Boolean> t) {
        for (int i=0; i<x.length; i++) {
            try {
                if (t.apply(x[i])) return true;
            } catch (Throwable throwable) {
                text_view.setText("Exception "+throwable.toString());
                throwable.printStackTrace();
            }
        }
        return false;
    }
    public String v_toString(double[] x) {
        StringBuilder result = new StringBuilder();
        result.append("["+x.length+"]{");
        for (int i = 0; i < x.length; i++) {
            if (i!=0) {
                result.append(", ");
            }
            result.append(""+i+":"+x[i]);
        }
        result.append("}");
        return result.toString();
    }
    // p are coefficients
    // x are values for x
    public double[] polyVal(double[] p, double[] x) {
        double[] result = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            result[i] = 0;
            for (int j = 0; j < p.length; j++) {
                double product = 1;
                int exponent = p.length - j - 1;
                for (int k = 0; k < exponent; k++) {
                    product *= x[i];
                }
                result[i] += p[j] * product;
            }
        }
        return result;
    }
    public double[] v_zero(int l) {
        double result[] = new double[l];
        for (int i = 0; i < l; i++) {
            result[i] = 0;
        }
        return result;
    }
    public double[] v_cumsum(double[] x) {
        double result[] = new double[x.length];
        double acc = 0;
        for (int i = 0; i < x.length; i++) {
            acc = acc + x[i];
            result[i] = acc;
        }
        return result;
    }
    //
    public double[] v_cumsum(double c, double[] x) {
        double result[] = new double[x.length + 1];
        result[0] = c;
        for (int i = 0; i < x.length; i++) {
            result[i+1] = result[i] + x[i];
        }
        return result;
    }
    public double[] v_subscalar(double[] x, double y) {
        double result[] = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            result[i] = x[i] - y;
        }
        return result;
    }
    // return x[x_offset...x_offset+length] - y
    public double[] v_slice(double[] x, int x_offset, int length) {
        double result[] = new double[length];
        for (int i = 0; i < length; i++) {
            result[i] = x[x_offset + i];
        }
        return result;
    }
    public double[] v_tail(double[] x) {
        //Log.d(TAG, "v_subtract length  "+length);
        double result[] = new double[x.length - 1];
        for (int i = 1; i < x.length; i++) {
            result[i-1] = x[i];
        }
        //Log.d(TAG,"v_subtract returning "+v_toString(result));
        return result;
    }
    public double[] v_subtract(double[] x, double[] y, int x_offset, int length) {
        if (length != y.length) throw new IllegalArgumentException(("vector subtraction of unequal lengths"));
        //Log.d(TAG, "v_subtract length  "+length);
        double result[] = new double[length];
        for (int i = 0; i < length; i++) {
            result[i] = x[x_offset + i] - y[i];
        }
        //Log.d(TAG,"v_subtract returning "+v_toString(result));
        return result;
    }
    public double[] v_subtract(double[] x, double[] y) {
        return v_subtract(x,y,0,x.length);
    }
    public double v_sum(double[] x) {
        double sum = 0;
        for (int i = 0; i < x.length; i++) {
            //Log.d(TAG,"v_sum "+i+" "+x[i]);
            sum += x[i];
        }
        //Log.d(TAG,"v_sum "+sum);
        return sum;
    }
    public double v_mean(double[] x) {
        double result = ((double)v_sum(x)) / x.length;
        //Log.d(TAG,"v_mean ("+v_toString(x)+") == "+result);
        return result;
    }
    public double[] v_abs(double[] x) {
        double[] result = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            result[i] = abs(x[i]);
        }
        //Log.d(TAG,"v_abs ("+v_toString(x)+") == "+result);
        return result;
    }
    // aka dRR
    public double[] v_differential(double[] x) {
        double[] result = new double[x.length - 1];
        for (int i = 0; i < (x.length - 1); i++) {
            result[i] = x[i+1] - x[i];
        }
        //Log.d(TAG,"v_diff ("+v_toString(x)+"\n) == "+v_toString(result));
        return result;
    }
    public double[] v_power_s1(double x, double[] y) {
        double result[] = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            result[i] = pow(x, y[i]);
        }
        return result;
    }
    public double[] v_power_s2(double[] x, double y) {
        double[] result = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            result[i] = pow(x[i], y);
        }
        //Log.d(TAG,"v_power_s2 result "+v_toString(result));
        return result;
    }
    public double[] v_logN(double[] x, double n) {
        double result[] = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            result[i] = Math.log10(x[i]) / Math.log10(n);
        }
        return result;
    }
    // num: how many samples
    public double[] arange(int min, int max, int num) {
        double result[] = new double[num];
        double acc = min;
        double delta = ((double)(max * 1.0 - min * 1.0)) / num;
        for (int i = 0; i < num; i++) {
            result[i] = acc;
            acc += delta;
        }
        return result;
    }

    private double getRMSDetrended(double[] x, int scale, double[] scale_ax, int offset, boolean smoothN) {
        double[] xbox = v_slice(x, offset, scale);
        Log.d(TAG,"getDetrendedMeanV1 "+ scale +" cut@"+ offset +" xbox "+v_toString(xbox));
        //     coeff = np.polyfit(scale_ax, xcut, 1)
        double[] ybox = xbox;
        if (smoothN) {
            Log.d(TAG,"rms smoothn");
            ybox = smoothnDetrending(xbox);
        }
        double[] coeff = v_reverse(polyFit(scale_ax, ybox,1));
        //Log.d(TAG,"rmsd coeff "+v_toString(coeff));
        //     xfit = np.polyval(coeff, scale_ax)
        double[] xfit = polyVal(coeff, scale_ax);
        Log.d(TAG,"xfit "+xfit.length+" "+v_toString(xfit));
        //Log.d(TAG,"rmsd xfit "+v_toString(xfit));
        //     # detrending and computing RMS of each window
        //     rms[e] = np.sqrt(np.mean((xcut-xfit)**2))
        //double[] finalSegment = v_subtract(ybox, xfit, offset, scale);
        double[] finalSegment = v_subtract(ybox, xfit, 0, scale);
        Log.d(TAG,"getDetrendedMeanV1 final "+v_toString(finalSegment));
        double mean = v_mean(v_power_s2(finalSegment,2));
        return mean;
    }

    // RMS + detrending
    // - divide x into x.length/scale non-overlapping boxes of size scale
    public double[] rmsDetrended(double[] x, int scale, boolean smoothN) {
        //Log.d(TAG,"rms_detrended call, scale "+scale);
        int nrboxes = x.length / scale;
        // # making an array with data divided in windows
        // shape = (x.shape[0]//scale, scale)
        // X = np.lib.stride_tricks.as_strided(x,shape=shape)
        // # vector of x-axis points to regression
        // scale_ax = np.arange(scale)
        double scale_ax[] = arange(0, scale, scale);
        //Log.d(TAG,"rms_detrended scale_ax "+v_toString(scale_ax));
        // rms = np.zeros(X.shape[0])
        double rms[] = v_zero(nrboxes*2);
        // for e, xcut in enumerate(X):
        int offset = 0;
        for (int i = 0; i < nrboxes; i++) {
            // root mean square SUCCESSIVE DIFFERENCES
            double mean = getRMSDetrended(x, scale, scale_ax, offset, smoothN);
            rms[i] = sqrt(mean);
            //Log.d(TAG,"rmsd box "+i+" "+" "+rms[i]);
            offset += scale;
        }
        // boxes in reverse order starting with a "last" box aligned to the very end of the data
        offset = x.length - scale;
        for (int i = nrboxes; i < nrboxes*2; i++) {
            double mean = getRMSDetrended(x, scale, scale_ax, offset, smoothN);
            rms[i] = sqrt(mean);
            //Log.d(TAG,"rmsd box "+i+" "+" "+rms[i]);
            offset -= scale;
        }
        //Log.d(TAG,"rms_detrended all @scale "+scale+"("+rms.length+")"+v_toString(rms));
        //     return rms
        return rms;
    }

    private SimpleMatrix[] detrendingFactorMatrices = new SimpleMatrix[20];

    public SimpleMatrix detrendingFactorMatrix(int length) {
        Log.d(TAG, "detrendingFactorMatrix size "+length);
        int T = length;
        if (detrendingFactorMatrices[T] != null) {
            Log.d(TAG,"detrendingFactorMatrix returning pre-cached matrix length "+T);
            return detrendingFactorMatrices[T];
        }
        Log.d(TAG,"detrendingFactorMatrix calculating "+T);
        // lambda=500; https://internal-journal.frontiersin.org/articles/10.3389/fspor.2021.668812/full
        int lambda = 500;
        SimpleMatrix I = SimpleMatrix.identity(T);
        SimpleMatrix D2 = new SimpleMatrix(T - 2, T);
        String d2str = "";
        for (int i = 0; i < D2.numRows(); i++) {
            Log.d(TAG,"D2 row "+i);
            D2.set(i, i,1);
            D2.set(i,i+1, -2);
            D2.set(i, i+2, 1);
            for (int j = 0; j < D2.numCols(); j++) {
                d2str += ""+D2.get(i,j)+" ";
            }
            d2str += "\n";
        }
        Log.d(TAG,"D2["+length+"]:\n"+d2str);
        SimpleMatrix sum = I.plus(D2.transpose().scale(lambda*lambda).mult(D2));
        Log.d(TAG, "createDetrendingFactorMatrix inverse...");
        Log.d(TAG, "inverse done");
        detrendingFactorMatrices[T] = I.minus(sum.invert());
        SimpleMatrix result = detrendingFactorMatrices[T];
        Log.d(TAG, "detrendingFactorMatrix length returned "+result.toString());
        return result;
    }

    public double[] smoothnDetrending(double[] dRR) {
        Log.d(TAG,"smoothnDetrending dRR "+v_toString(dRR));
        // convert dRRs to vector (SimpleMatrix)
        SimpleMatrix dRRvec = new SimpleMatrix(dRR.length,1);
        for (int i = 0; i<dRR.length; i++) {
            dRRvec.set(i, 0, dRR[i]);
        }
        SimpleMatrix detrended = detrendingFactorMatrix(dRRvec.numRows()).mult(dRRvec);
        Log.d(TAG,"detrendingv2: "+detrended.numRows()+" "+detrended.numCols());
        int size = detrended.numRows();
        double[] result = new double[size];
        for (int i = 0; i<size; i++) {
            result[i] = detrended.get(i,0);
        }
        Log.d("TAG","detrendingV2 ("+v_toString(dRR)+")\n  == "+v_toString(result));
        return result;
    }

    // get the variance (average square-difference from mean) around the detrended walk of
    // the box denoted by @arg x at offset of length scale
    private double getBoxDetrendedRMS(double[] x, double[] scale_ax, int scale, int offset) {
        // FIXME: revert scale + 1?
        // original walk (displacement)
        double[] ycut = v_slice(x, offset, scale);
        Log.d(TAG,"getDetrendedMeanV2 ycut"+v_toString(ycut));
        // FIXME: with overlap, it looks like we lose at least one dRR at the extremities of each slice
        // length - 1
        //double[] dRR = v_differential(ycut);
        double mean = v_mean(ycut);
        double[] iRR = v_cumsum(v_subscalar(ycut,mean));
        // stationary component of dRR
        double[] stationary_dRR = smoothnDetrending(iRR);
        // "detrended walk" (displacement?)
        // == "the difference between the original walk and the local trend"
        // - original walk == ycut
        // - local trend == dRR - stationary_dRR (does not apply to ycut[0])
        // - detrended walk == ycut[1:] - (dRR - stationary_dRR)
        double[] detrended_walk = v_subtract(iRR,stationary_dRR);
        Log.d(TAG,"dfaAlpha1V2 getDetrendedWalkVarianceV2 detrended_walk "+v_toString(detrended_walk));
//        double mean2 = v_mean(detrended_walk);
//        double[] diffs = v_subscalar(detrended_walk, mean2);
        // mean of square of differences
        double meanv2 = sqrt(v_mean(v_power_s2(detrended_walk,2)));
        return meanv2;
    }

    // Find the variances of all "boxes" of size scale
    // - divide x into x.length/scale non-overlapping boxes of size scale
    // - and again in reverse
    // - actually for now the boxes overlap by 1 sample at each end
    public double[] getRMSatScale(double[] x, int scale) {
        //Log.d(TAG,"rmsDetrendedV2  "+v_toString(x)+" scale "+scale);
        int nrboxes = (x.length - 1) / scale;
        //Log.d(TAG,"rms_detrended scale_ax "+v_toString(scale_ax));
        double scale_ax[] = arange(0, scale, scale);
        // rms = np.zeros(X.shape[0])
        double variances[] = v_zero(nrboxes * 2);
        int offset = 0;
        // for e, xcut in enumerate(X):
        for (int i = 0; i < nrboxes; i++) {
            // root mean squared successive differences
            double variance = getBoxDetrendedRMS(x, scale_ax, scale, offset);
            variances[i] = variance;
            //Log.d(TAG,"- rmssd slice "+i+" "+" "+rms[i]);
            offset += scale;
        }
        // boxes in reverse order starting with a "last" box aligned to the very end of the data
        // to align with the very end, given "overlap", we need to come back an additional 1 sample
        offset = x.length - scale - 1;
        for (int i = nrboxes; i < nrboxes*2; i++) {
            // root mean squared successive differences
            double rms = sqrt(getBoxDetrendedRMS(x, scale_ax, scale, offset));
            variances[i] = rms;
            //Log.d(TAG,"- rmssd slice "+i+" "+" "+rms[i]);
            offset -= scale;
        }
        Log.d(TAG,"rmsDetrendedV2 scale "+scale+" "+v_toString(variances));
        //     return rms
        return variances;
    }

    public double dfaAlpha1V2(double x[], int l_lim, int u_lim, int nrscales) {
        return dfaAlpha1V1(x,l_lim,u_lim,nrscales,true);
    }

        // x: dRR samples; l_lim: lower limit; u_lim: upper limit
    public double dfaAlpha1V2_old(double x[], int l_lim, int u_lim, int nrscales) {
        Log.d(TAG, "dfaAlpha1V2");
        // scales = (2**np.arange(scale_lim[0], scale_lim[1], scale_dens)).astype(np.int)
        double[] scales = v_power_s1(2, arange(l_lim,u_lim,nrscales));
        // FIXME: reinstate scale 3??
//        double[] exp_scales = { 3.,  4.,  4.,  4.,  4.,  5.,  5.,  5.,  5.,  6.,  6.,  6.,  7.,  7.,  7.,  8.,  8.,  9.,
//                9.,  9., 10., 10., 11., 12., 12., 13., 13., 14., 15., 15.};
        double[] exp_scales = { 3., 4.,  4.,  4.,  4.,  5.,  5.,  5.,  5.,  6.,  6.,  6.,  7.,  7.,  7.,  8.,  8.,  9.,
                9.,  9., 10., 10., 11., 12., 12., 13., 13., 14., 15., 15.};
        // HACK - we know what scales are needed for now
        scales = exp_scales;
        if (scales != exp_scales) {
            text_view.setText("IllegalStateException: wrong scales");
            throw new IllegalStateException("wrong scales");
        }
        Log.d(TAG, "dfaAlpha1V2 scales "+v_toString(scales));
        // fluct = np.zeros(len(scales))
        double[] fluct = v_zero(scales.length);
        for (int i = 0; i < scales.length; i++) {
            int sc = (int)(scales[i]);
            //Log.d(TAG, "- scale "+i+" "+sc);
            double[] rms = getRMSatScale(x, sc);
            fluct[i] = sqrt(v_mean(v_power_s2(rms,2)));
        }
        //Log.d(TAG, "Polar dfa_alpha1, x "+v_toString(x));
        Log.d(TAG, "dfaAlpha1V2 fluct: "+v_toString(fluct));
        // # fitting a line to rms data
//        double[] coeff = v_reverse(polyFit(v_log2(scales), v_log2(fluct), 1));
        double[] coeff = v_reverse(polyFit(v_logN(scales,2), v_logN(fluct, 2), 1));
        Log.d(TAG, "dfaAlpha1V2 coefficients "+v_toString(coeff));
        double alpha = coeff[0];
        Log.d(TAG, "dfaAlpha1V2 = "+alpha);
        return alpha;
    }

    // x: samples; l_lim: lower limit; u_lim: upper limit
    public double dfaAlpha1V1(double x[], int l_lim, int u_lim, int nrscales, boolean smoothN) {
        // vector: cumulative sum, elmtwise-subtract, elmtwise-power, mean, interpolate, RMS, Zero, Interpolate
        // sqrt
        // polynomial fit
        // # Python from https://github.com/dokato/dfa/blob/master/dfa.py
        // y = np.cumsum(x - np.mean(x))
        Log.d(TAG, "dfaAlpha1V1...");
        double mean = v_mean(x);
        Log.d(TAG, "dfaAlpha1 mean "+mean);
        double[] y = v_cumsum(v_subscalar(x, mean));
        Log.d(TAG, "Polar alpha1 y "+v_toString(y));
        // scales = (2**np.arange(scale_lim[0], scale_lim[1], scale_dens)).astype(np.int)
        double[] scales = v_power_s1(2, arange(l_lim,u_lim,nrscales));
        double[] exp_scales = { 3.,  4.,  4.,  4.,  4.,  5.,  5.,  5.,  5.,  6.,  6.,  6.,  7.,  7.,  7.,  8.,  8.,  9.,
                9.,  9., 10., 10., 11., 12., 12., 13., 13., 14., 15., 15.};
        // HACK - we know what scales are needed for now
        scales = exp_scales;
        if (scales != exp_scales) {
            text_view.setText("IllegalStateException: wrong scales");
            throw new IllegalStateException("wrong scales");
        }
        // fluct = np.zeros(len(scales))
        double[] fluct = v_zero(scales.length);
        // # computing RMS for each window
        // for e, sc in enumerate(scales):
        //   fluct[e] = np.sqrt(np.mean(calc_rms(y, sc)**2))
        for (int i = 0; i < scales.length; i++) {
            int sc = (int)(scales[i]);
            //Log.d(TAG, "- scale "+i+" "+sc);
            double[] sc_rms = rmsDetrended(y, sc, smoothN);
            fluct[i] = sqrt(v_mean(v_power_s2(sc_rms,2)));
            //Log.d(TAG, "  - rms "+v_toString(sc_rms));
            //Log.d(TAG, "  - scale "+i+" "+sc+" fluct "+fluct[i]);
        }
        //Log.d(TAG, "Polar dfa_alpha1, x "+v_toString(x));
        Log.d(TAG, "dfa_alpha1, scales "+v_toString(scales));
        Log.d(TAG, "dfa_alpha1 fluct: "+v_toString(fluct));
        // # fitting a line to rms data
        double[] coeff = v_reverse(polyFit(v_logN(scales,2), v_logN(fluct,2), 1));
        Log.d(TAG, "dfa_alpha1 coefficients "+v_toString(coeff));
        double alpha = coeff[0];
        Log.d(TAG, "dfa_alpha1 = "+alpha);
        return alpha;
    }

    public void testDFA_alpha1() {
        text_view.setText("Self-test DFA alpha1");
        Log.d(TAG,"testDFA_alpha1");
        double[] values = {635.0, 628.0, 627.0, 625.0, 624.0, 627.0, 624.0, 623.0, 633.0, 636.0, 633.0, 628.0, 625.0, 628.0, 622.0, 621.0, 613.0, 608.0, 604.0, 612.0, 620.0, 616.0, 611.0, 616.0, 614.0, 622.0, 627.0, 625.0, 622.0, 617.0, 620.0, 622.0, 623.0, 615.0, 614.0, 627.0, 630.0, 632.0, 632.0, 632.0, 631.0, 627.0, 629.0, 634.0, 628.0, 625.0, 629.0, 633.0, 632.0, 628.0, 631.0, 631.0, 628.0, 623.0, 619.0, 618.0, 618.0, 628.0, 634.0, 631.0, 626.0, 633.0, 637.0, 636.0, 632.0, 634.0, 625.0, 614.0, 610.0, 607.0, 613.0, 616.0, 622.0, 625.0, 620.0, 633.0, 640.0, 639.0, 631.0, 626.0, 634.0, 628.0, 615.0, 610.0, 607.0, 611.0, 613.0, 614.0, 611.0, 608.0, 627.0, 625.0, 619.0, 618.0, 622.0, 625.0, 626.0, 625.0, 626.0, 624.0, 631.0, 631.0, 619.0, 611.0, 608.0, 607.0, 602.0, 586.0, 583.0, 576.0, 580.0, 571.0, 583.0, 591.0, 598.0, 607.0, 607.0, 621.0, 619.0, 622.0, 613.0, 604.0, 607.0, 603.0, 604.0, 598.0, 595.0, 592.0, 589.0, 594.0, 594.0, 602.0, 611.0, 614.0, 634.0, 635.0, 636.0, 628.0, 627.0, 628.0, 626.0, 619.0, 616.0, 616.0, 622.0, 615.0, 607.0, 611.0, 610.0, 619.0, 624.0, 625.0, 626.0, 633.0, 643.0, 647.0, 644.0, 644.0, 642.0, 645.0, 637.0, 628.0, 632.0, 633.0, 625.0, 626.0, 623.0, 620.0, 620.0, 610.0, 612.0, 612.0, 610.0, 614.0, 611.0, 609.0, 616.0, 624.0, 623.0, 618.0, 622.0, 623.0, 625.0, 629.0, 621.0, 622.0, 617.0, 619.0, 618.0, 610.0, 607.0, 606.0, 611.0};
        // Altini Python code
        // double exp_result = 1.5503173309573208;
        // FIXME: tiny discrepancy in double precision between Python and Java impl(?!)
        // This Java impl:
        double exp_result = 1.5503173309573228;
        double act_result = dfaAlpha1V1(values,2,4,30, false);
        if (Double.compare(exp_result,act_result)!=0) {
            String msg ="expected "+exp_result+" got "+act_result;
            text_view.setText("Self-test DFA alpha1 failed: "+msg);
            Log.d(TAG,"***** testDFA_alpha1 failed "+msg+" *****");
            throw new IllegalStateException("test failed, expected "+exp_result+" got "+act_result);
        } else {
            text_view.setText("Self-test DFA alpha1 passed");
            Log.d(TAG, "Self-test DFA alpha1 passed");
        }
    }

    TextView text_view;
    TextView text_time;
    TextView text_batt;
    TextView text_mode;
    TextView text_hr;
    TextView text_hrv;
    TextView text_a1;
    TextView text_artifacts;

    public boolean experimental = false;
    // 120s ring buffer for dfa alpha1
    public final int featureWindowSizeSec = 120;
    // buffer to allow at least 45 beats forward/backward per Kubios
    public final int sampleBufferMarginSec = 45;
    //public final int rrWindowSizeSec = featureWindowSizeSec + sampleBufferMarginSec;
    public final int rrWindowSizeSec = featureWindowSizeSec;
    // time between alpha1 calculations
    public int alpha1EvalPeriod;
    // max hr approx 300bpm(!?) across 120s window
    // FIXME: this is way larger than needed
    public final int maxrrs = 300 * rrWindowSizeSec;
    // circular buffer of recently recorded RRs
    // FIXME: premature optimization is the root of all evil
    // Not that much storage required, does not avoid the fundamental problem that
    // our app gets paused anyway
    public double[] rrInterval = new double[maxrrs];
    // timestamp of recently recorded RR (in ms since epoch)
    public long[] rrIntervalTimestamp = new long[maxrrs];
    // timestamps of artifact
    private long[] artifactTimestamp = new long[maxrrs];
    // oldest and newest recorded RRs in the recent window
    public int oldestSample = 0;
    public int newestSample = 0;
    // oldest and newest recorded artifact( timestamp)s in the recent window
    public int oldestArtifactSample = 0;
    public int newestArtifactSample = 0;
    // time first sample received in MS since epoch
    public long firstSampleMS;
    // have we started sampling?
    public boolean started = false;
    double rmssdWindowed = 0;
    // last known alpha1 (default resting nominally 1.0)
    double alpha1Windowed = 1.0;
    double alpha1RoundedWindowed = 1.0;
    double alpha1WindowedV2 = 1.0;
    double alpha1RoundedWindowedV2 = 1.0;
    int artifactsPercentWindowed;
    int currentHR;
    double hrMeanWindowed = 0;
    double rrMeanWindowed = 0;
    // maximum tolerable variance of adjacent RR intervals
    double artifactCorrectionThreshold = 0.05;
    // elapsed time in terms of cumulative sum of all seen RRs (as for HRVLogger)
    long logRRelapsedMS = 0;
    // the last time (since epoch) a1 was evaluated
    public long prevA1Timestamp = 0;
    public double prevrr = 0;
    public boolean starting = false;
    public long prevSpokenUpdateMS = 0;
    public int totalRejected = 0;
    public boolean thisIsFirstSample = false;
    long currentTimeMS;
    long elapsedMS;
    long elapsedSeconds;

    static NotificationManagerCompat uiNotificationManager;
    static NotificationCompat.Builder uiNotificationBuilder;

    private TextToSpeech ttobj;
    MediaPlayer mp;

    private FileWriter rrLogStreamNew;
    private FileWriter featureLogStreamNew;

    private FileWriter rrLogStreamLegacy;
    private FileWriter featureLogStreamLegacy;

    private FileWriter debugLogStream;

    private class Log {
        public int d(String tag, String msg) {
            if (debugLogStream!=null) writeLogFile( msg, debugLogStream,  "debug");
            return android.util.Log.d(tag,msg);
        }
        public int e(String tag, String msg) {
            if (debugLogStream!=null) writeLogFile( msg, debugLogStream, "debug");
            return android.util.Log.e(tag,msg);
        }
    }
    private static Log Log;

    private void closeLog(FileWriter fw) {
        try {
            fw.close();
        } catch (IOException e) {
            Log.d(TAG,"IOException closing "+fw.toString()+": "+e.toString());
        }
    }

    private void closeLogs() {
        closeLog(rrLogStreamNew);
        closeLog(rrLogStreamLegacy);
        closeLog(featureLogStreamNew);
        closeLog(featureLogStreamLegacy);
    }

    PowerManager powerManager;
    PowerManager.WakeLock wakeLock;

    //    PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
//    PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");

    ScrollView scrollView;

    GraphView graphView;
    LineGraphSeries<DataPoint> hrSeries = new LineGraphSeries<DataPoint>();
    LineGraphSeries<DataPoint> a1Series = new LineGraphSeries<DataPoint>();
    LineGraphSeries<DataPoint> a1V2Series = new LineGraphSeries<DataPoint>();
    LineGraphSeries<DataPoint> a1HRVvt1Series = new LineGraphSeries<DataPoint>();
    LineGraphSeries<DataPoint> a1HRVvt2Series = new LineGraphSeries<DataPoint>();
    LineGraphSeries<DataPoint> a125Series = new LineGraphSeries<DataPoint>();
    LineGraphSeries<DataPoint> a1125Series = new LineGraphSeries<DataPoint>();
    LineGraphSeries<DataPoint> a1175Series = new LineGraphSeries<DataPoint>();
    //LineGraphSeries<DataPoint> hrvSeries = new LineGraphSeries<DataPoint>();
    LineGraphSeries<DataPoint> artifactSeries = new LineGraphSeries<DataPoint>();
    final int maxDataPoints = 65535;
    final double graphViewPortWidth = 2.0;
    final int graphMaxHR = 200;
    final int graphMaxErrorRatePercent = 10;

    /**
     * Return date in specified format.
     * @param milliSeconds Date in milliseconds
     * @param dateFormat Date format
     * @return String representing date in specified format
     */
    public static String getDate(long milliSeconds, String dateFormat) {
        // Create a DateFormatter object for displaying date in specified format.
        SimpleDateFormat formatter = new SimpleDateFormat(dateFormat);
        // Create a calendar object that will convert the date and time value in milliseconds to date.
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(milliSeconds);
        return formatter.format(calendar.getTime());
    }

    // FIXME: enum
    final int MENU_QUIT = 0;
    final int MENU_SEARCH = 1;
    final int MENU_CONNECT_DEFAULT = 2;
    final int MENU_EXPORT = 3;
    final int MENU_EXPORT_ALL = 4;
    final int MENU_DELETE_ALL = 5;
    final int MENU_EXPORT_DEBUG = 6;
    final int MENU_DELETE_DEBUG = 7;
    final int MENU_OLD_LOG_FILES = 8;
    final int MENU_TAG_FOR_EXPORT = 9;
    final int MENU_LIST_FILES = 10;
    final int MENU_EXPORT_TAGGED = 11;
    // ...
    final int MENU_CONNECT_DISCOVERED = 100;

    // collect devices by deviceId so we don't spam the menu
    Map<String,String> discoveredDevices = new HashMap<String,String>();
    Map<Integer,String> discoveredDevicesMenu = new HashMap<Integer,String>();

    /**
     * Gets called every time the user presses the menu button.
     * Use if your menu is dynamic.
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        menu.add(0, MENU_QUIT, Menu.NONE, "Quit");
        if (sharedPreferences.getBoolean("experimental",false)) {
            menu.add(0, MENU_TAG_FOR_EXPORT, Menu.NONE, "Tag Current Logs For Export");
            menu.add(0, MENU_EXPORT_TAGGED, Menu.NONE, "Export Tagged Logs");
        }
        menu.add(0, MENU_EXPORT, Menu.NONE, "Export Current Logs");
        menu.add(0, MENU_OLD_LOG_FILES, Menu.NONE, "Delete Old Logs");
        menu.add(0, MENU_EXPORT_DEBUG, Menu.NONE, "Export Debug Logs");
        menu.add(0, MENU_DELETE_DEBUG, Menu.NONE, "Delete Debug Logs");
        menu.add(0, MENU_EXPORT_ALL, Menu.NONE, "Export All Logs");
        menu.add(0, MENU_DELETE_ALL, Menu.NONE, "Delete All Logs");
        //menu.add(0, MENU_LIST_FILES, Menu.NONE, "List Stored Logs");
        String tmpDeviceId = sharedPreferences.getString("polarDeviceID","");
        if (tmpDeviceId.length()>0) {
            menu.add(0, MENU_CONNECT_DEFAULT, Menu.NONE, "Connect preferred device "+tmpDeviceId);
        }
        int i = 0;
        for (String tmpDeviceID : discoveredDevices.keySet()) {
            menu.add(0, MENU_CONNECT_DISCOVERED + i, Menu.NONE, "Connect "+discoveredDevices.get(tmpDeviceID));
            discoveredDevicesMenu.put(MENU_CONNECT_DISCOVERED + i,tmpDeviceID);
            i++;
        }
        menu.add(0, MENU_SEARCH, Menu.NONE, "Search for Polar devices");
        return super.onPrepareOptionsMenu(menu);
    }

    public Uri getUri(File f) {
        try {
            return FileProvider.getUriForFile(
                    MainActivity.this,
                    "polar.com.alpha1.fileprovider",
                    f);
        } catch (IllegalArgumentException e) {
            Log.e("File Selector",
                    "The selected file can't be shared: " + f.toString());
        }
        return null;
    }

    public void tagCurrentLogsForExport() {
        Set<String> logsToExport = sharedPreferences.getStringSet("logsToExport",null);
        if (logsToExport==null) {
            logsToExport = new HashSet<String>();
        }
        logsToExport.add(currentLogFiles.get("debug").getAbsolutePath());
        logsToExport.add(currentLogFiles.get("features").getAbsolutePath());
        logsToExport.add(currentLogFiles.get("rr").getAbsolutePath());
        sharedPreferences.edit().putStringSet("logsToExport", logsToExport).commit();
    }

    public void exportTaggedFiles() {
        ArrayList<Uri> logUris = new ArrayList<Uri>();
        for (String path : sharedPreferences.getStringSet("logsToExport",null)) {
                logUris.add(getUri(new File(path)));
        }
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, logUris);
        shareIntent.setType("text/plain");
        final int exportLogFilesCode = 254;
        startActivityForResult(Intent.createChooser(shareIntent, "Share log files to.."), exportLogFilesCode);
    }

    public void exportLogFiles() {
        ArrayList<Uri> logUris = new ArrayList<Uri>();
        logUris.add(getUri(currentLogFiles.get("rr")));
        logUris.add(getUri(currentLogFiles.get("features")));

        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, logUris);
        shareIntent.setType("text/plain");
        final int exportLogFilesCode = 254;
        startActivityForResult(Intent.createChooser(shareIntent, "Share log files to.."), exportLogFilesCode);
    }

    // all but current logs
    public ArrayList<Uri> logFiles() {
        Log.d(TAG,"logFiles...");
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File privateRootDir = getFilesDir();
        privateRootDir.mkdir();
        File logsDir = new File(privateRootDir, "logs");
        logsDir.mkdir();
        File[] allFiles = logsDir.listFiles();
        for (File f : allFiles) {
            Log.d(TAG,"Found log file: "+getUri(f));
            allUris.add(getUri(f));
        }
        return allUris;
    }

    // all but current logs
    public ArrayList<Uri> oldLogFiles() {
        Log.d(TAG,"oldLogFiles...");
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File privateRootDir = getFilesDir();
        privateRootDir.mkdir();
        File logsDir = new File(privateRootDir, "logs");
        logsDir.mkdir();
        File[] allFiles = logsDir.listFiles();
        for (File f : allFiles) {
            Log.d(TAG,"Found log file: "+getUri(f));
            if (currentLogFiles.values().contains(f)) {
                Log.d(TAG,"- skipping current log");
            } else {
                allUris.add(getUri(f));
            }
        }
        return allUris;
    }

    // all but current debug files
    public ArrayList<Uri> oldDebugFiles() {
        Log.d(TAG,"oldLogFiles...");
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File privateRootDir = getFilesDir();
        privateRootDir.mkdir();
        File logsDir = new File(privateRootDir, "logs");
        logsDir.mkdir();
        File[] allFiles = logsDir.listFiles();
        for (File f : allFiles) {
            Log.d(TAG,"Found log file: "+getUri(f));
            if (f.equals(currentLogFiles.get("debug"))) {
                Log.d(TAG,"- skipping current debug file");
            } else {
                allUris.add(getUri(f));
            }
        }
        return allUris;
    }

    public void exportFiles(ArrayList<Uri> allUris) {
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, allUris);
        shareIntent.setType("text/plain");
        startActivity(Intent.createChooser(shareIntent, "Share log files to.."));
    }

    public void exportAllLogFiles() {
        Log.d(TAG,"exportAllLogFiles...");
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File privateRootDir = getFilesDir();
        privateRootDir.mkdir();
        File logsDir = new File(privateRootDir, "logs");
        logsDir.mkdir();
        File[] allFiles = logsDir.listFiles();
        for (File f : allFiles) {
            Log.d(TAG,"Found log file: "+getUri(f));
            allUris.add(getUri(f));
        }
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, allUris);
        shareIntent.setType("text/plain");
        startActivity(Intent.createChooser(shareIntent, "Share log files to.."));
    }

    public void deleteOldLogFiles() {
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File privateRootDir = getFilesDir();
        privateRootDir.mkdir();
        File logsDir = new File(privateRootDir, "logs");
        logsDir.mkdir();
        File[] allFiles = logsDir.listFiles();
        StringBuilder filenames = new StringBuilder();
        for (File f : allFiles) {
            if (!currentLogFiles.containsValue(f)) {
                Log.d(TAG,"deleting log file "+f);
                f.delete();
                filenames.append(f.getName()+" ");
            }
        }
        Toast.makeText(getBaseContext(), "Deleted "+filenames.toString(), Toast.LENGTH_LONG).show();
    }

    public void deleteAllLogFiles() {
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File privateRootDir = getFilesDir();
        privateRootDir.mkdir();
        File logsDir = new File(privateRootDir, "logs");
        logsDir.mkdir();
        File[] allFiles = logsDir.listFiles();
        StringBuilder filenames = new StringBuilder();
        for (File f : allFiles) {
            if (!currentLogFiles.containsValue(f)) {
                Log.d(TAG,"deleting log file "+f);
                f.delete();
                filenames.append(f.getName()+" ");
            }
        }
        Toast.makeText(getBaseContext(), "Deleted "+filenames.toString(), Toast.LENGTH_LONG).show();
    }

    public void exportAllDebugFiles() {
        Log.d(TAG,"exportAllDebugFiles...");
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File privateRootDir = getFilesDir();
        privateRootDir.mkdir();
        File logsDir = new File(privateRootDir, "logs");
        logsDir.mkdir();
        File[] allFiles = logsDir.listFiles();
        for (File f : allFiles) {
            //Log.d(TAG, "Debug file? " + getUri(f));
            if (f.getName().endsWith(".debug.log")) {
                Log.d(TAG, "Found debug file: " + getUri(f));
                allUris.add(getUri(f));
            }
        }
        Intent shareIntent = new Intent();
        shareIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
        shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, allUris);
        shareIntent.setType("text/plain");
        startActivity(Intent.createChooser(shareIntent, "Share log files to.."));
    }

    public void deleteAllDebugFiles() {
        ArrayList<Uri> allUris = new ArrayList<Uri>();
        File privateRootDir = getFilesDir();
        privateRootDir.mkdir();
        File logsDir = new File(privateRootDir, "logs");
        logsDir.mkdir();
        File[] allFiles = logsDir.listFiles();
        StringBuilder filenames = new StringBuilder();
        for (File f : allFiles) {
            if (f.getName().endsWith(".debug.log") && !currentLogFiles.containsValue(f)) {
                Log.d(TAG,"deleting log file (on exit) "+f);
                f.deleteOnExit();
                filenames.append(f.getName()+" ");
            }
        }
        Toast.makeText(getBaseContext(), "Deleting (on exit) "+filenames.toString(), Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable @org.jetbrains.annotations.Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Toast.makeText(getBaseContext(), "Activity result ", Toast.LENGTH_SHORT).show();
    }

    //    public boolean onCreateOptionsMenu(Menu menu) {
    //        MenuInflater inflater = getMenuInflater();
    //        inflater.inflate(R.menu.options_menu, menu);
    //        return true;
    //    }
    public boolean onOptionsItemSelected(MenuItem item) {
        //respond to menu item selection
        Log.d(TAG, "onOptionsItemSelected... "+item.getItemId());
        int itemID = item.getItemId();
        if (itemID == MENU_QUIT) finish();
        if (itemID == MENU_TAG_FOR_EXPORT) tagCurrentLogsForExport();
        if (itemID == MENU_EXPORT_TAGGED) exportTaggedFiles();
        if (itemID == MENU_EXPORT) exportLogFiles();
        if (itemID == MENU_EXPORT_ALL) exportAllLogFiles();
        if (itemID == MENU_DELETE_ALL) deleteAllLogFiles();
        if (itemID == MENU_EXPORT_DEBUG) exportAllDebugFiles();
        if (itemID == MENU_DELETE_DEBUG) deleteAllDebugFiles();
        if (itemID == MENU_CONNECT_DEFAULT) tryPolarConnect();
        if (itemID == MENU_OLD_LOG_FILES) deleteOldLogFiles();
        //if (itemID == MENU_LIST_FILES) listFiles(logFiles());
        if (itemID == MENU_SEARCH) searchForPolarDevices();
        if (discoveredDevicesMenu.containsKey(item.getItemId())) {
            tryPolarConnect(discoveredDevicesMenu.get(item.getItemId()));
        }
        return super.onOptionsItemSelected(item);
    }

    public void quitSearchForPolarDevices() {
        broadcastDisposable.dispose();
        broadcastDisposable = null;
    }

    public void searchForPolarDevices() {
        text_view.setText("Searching for Polar devices...");
        if (broadcastDisposable == null) {
            broadcastDisposable = api.startListenForPolarHrBroadcasts(null)
                    .subscribe(polarBroadcastData -> {
                                    if (!discoveredDevices.containsKey(polarBroadcastData.polarDeviceInfo.deviceId)) {
                                        String desc = polarBroadcastData.polarDeviceInfo.name;
                                        String msg = "Discovered " + desc + " HR " + polarBroadcastData.hr;
                                        discoveredDevices.put(polarBroadcastData.polarDeviceInfo.deviceId,desc);
                                        text_view.setText(msg);
                                        Log.d(TAG, msg);
                                    }
                                },
                                error -> {
                                    Log.e(TAG, "Broadcast listener failed. Reason: " + error);
                                },
                                () -> {
                                    Log.d(TAG, "complete");
                                });
        } else {
            broadcastDisposable.dispose();
            broadcastDisposable = null;
        }
    }

    //@RequiresApi(Build.VERSION_CODES.O)
    private String createUINotificationChannel() {
        NotificationChannel chan = new NotificationChannel(UI_CHANNEL_ID,
                UI_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
//            chan.lightColor = Color.BLUE;
//            chan.lockscreenVisibility = Notification.VISIBILITY_PRIVATE;
        NotificationManager service = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        service.createNotificationChannel(chan);
        return UI_CHANNEL_ID;
    }

    public void handleUncaughtException(Thread thread, Throwable e) {
        Log.d(TAG,"Uncaught exception "+e.toString()+" "+e.getStackTrace().toString()); // not all Android versions will print the stack trace automatically
        //
        Intent intent = new Intent ();
        intent.setAction ("com.mydomain.SEND_LOG"); // see step 5.
        intent.setFlags (Intent.FLAG_ACTIVITY_NEW_TASK); // required when starting from Application
        startActivity (intent);
        //
        System.exit(1); // kill off the crashed app
    }

    @Override
    protected void onCreate (Bundle savedInstanceState) {
        // Setup handler for uncaught exceptions.
        Thread.setDefaultUncaughtExceptionHandler (new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException (Thread thread, Throwable e) {
                handleUncaughtException (thread, e);
            }
        });

        super.onCreate(savedInstanceState);
        uiNotificationManager = NotificationManagerCompat.from(this);
        Intent i = new Intent(MainActivity.this, LocalService.class);
        i.setAction("START");
        Log.d(TAG,"intent to start local service "+i);
        ComponentName serviceComponentName = MainActivity.this.startService(i);
        Log.d(TAG,"start result "+serviceComponentName);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setIcon(R.mipmap.ic_launcher_foreground);

        //setContentView(R.layout.activity_fragment_container);
        setContentView(R.layout.activity_main);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        doBindService();
        createUINotificationChannel();

        // Notice PolarBleApi.ALL_FEATURES are enabled
        //api = PolarBleApiDefaultImpl.defaultImplementation(this, PolarBleApi.ALL_FEATURES);
        api = PolarBleApiDefaultImpl.defaultImplementation(this,
                PolarBleApi.FEATURE_HR | PolarBleApi.FEATURE_BATTERY_INFO);
        api.setPolarFilter(false);

        final Button connect = this.findViewById(R.id.connect_button);
        connect.setVisibility(View.GONE);
        final Button speech_on = this.findViewById(R.id.speech_on_button);
        speech_on.setVisibility(View.GONE);
        final Button speech_off = this.findViewById(R.id.speech_off_button);
        speech_off.setVisibility(View.GONE);
        final Button test_feature = this.findViewById(R.id.testFeature_button);
        test_feature.setVisibility(View.GONE);
        final Button setDeviceIDButton = this.findViewById(R.id.setDeviceIdButton);
        setDeviceIDButton.setVisibility(View.GONE);
        final Button broadcast = this.findViewById(R.id.broadcast_button);
        broadcast.setVisibility(View.GONE);
        final Button disconnect = this.findViewById(R.id.disconnect_button);
        disconnect.setVisibility(View.GONE);
        final Button autoConnect = this.findViewById(R.id.auto_connect_button);
        autoConnect.setVisibility(View.GONE);
        final Button ecg = this.findViewById(R.id.ecg_button);
        ecg.setVisibility(View.GONE);
        final Button acc = this.findViewById(R.id.acc_button);
        acc.setVisibility(View.GONE);
        final Button gyr = this.findViewById(R.id.gyr_button);
        gyr.setVisibility(View.GONE);
        final Button mag = this.findViewById(R.id.mag_button);
        mag.setVisibility(View.GONE);
        final Button ppg = this.findViewById(R.id.ohr_ppg_button);
        ppg.setVisibility(View.GONE);
        final Button ppi = this.findViewById(R.id.ohr_ppi_button);
        ppi.setVisibility(View.GONE);
        final Button scan = this.findViewById(R.id.scan_button);
        scan.setVisibility(View.GONE);
        final Button list = this.findViewById(R.id.list_exercises);
        list.setVisibility(View.GONE);
        final Button read = this.findViewById(R.id.read_exercise);
        read.setVisibility(View.GONE);
        final Button remove = this.findViewById(R.id.remove_exercise);
        remove.setVisibility(View.GONE);
        final Button startH10Recording = this.findViewById(R.id.start_h10_recording);
        startH10Recording.setVisibility(View.GONE);
        final Button stopH10Recording = this.findViewById(R.id.stop_h10_recording);
        stopH10Recording.setVisibility(View.GONE);
        final Button readH10RecordingStatus = this.findViewById(R.id.h10_recording_status);
        readH10RecordingStatus.setVisibility(View.GONE);

        text_time = this.findViewById(R.id.timeView);
        text_batt = this.findViewById(R.id.battView);
        text_mode = this.findViewById(R.id.modeView);
        text_hr = this.findViewById(R.id.hrTextView);
        //text_hr.setText("\u2764"+"300");
        text_hrv = this.findViewById(R.id.hrvTextView);
        text_a1 = this.findViewById(R.id.a1TextView);
        text_artifacts = this.findViewById(R.id.artifactsView);
        text_view = this.findViewById(R.id.textView);

        //text.setTextSize(100);
        //text.setMovementMethod(new ScrollingMovementMethod());
        // text.setText(message);
        text_view.setText("Text output goes here...");

        scrollView = this.findViewById(R.id.application_container);
        // FIXME: Why does the scrollable not start with top visible?
        // scrollView.scrollTo(0,0);

        /////
        /////
        /////
        /////
        //testDFA_alpha1();
        //testRMSSD_1();
        /////
        /////
        /////
        /////

        api.setApiLogger(s -> Log.d(API_LOGGER_TAG, s));

        Log.d(TAG, "version: " + PolarBleApiDefaultImpl.versionInfo());

        ttobj = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                ttobj.setLanguage(Locale.UK);
                nonScreenUpdate("Voice output ready");
            }
        });

        rrLogStreamLegacy = createLogFile("rr");
        featureLogStreamLegacy = createLogFile("features");

        rrLogStreamNew = createLogFileNew("rr","csv");
        writeLogFiles("timestamp, rr, since_start ", rrLogStreamNew, rrLogStreamLegacy, "rr");
        writeLogFiles("", rrLogStreamNew, rrLogStreamLegacy, "rr");
        featureLogStreamNew = createLogFileNew("features","csv");
        writeLogFiles("timestamp,heartrate,rmssd,sdnn,alpha1,filtered,samples,droppedPercent,artifactThreshold,alpha1v2", featureLogStreamNew, featureLogStreamLegacy, "features");
        debugLogStream = createLogFileNew("debug","log");

        mp = MediaPlayer.create(this, R.raw.artifact);
        mp.setVolume(100, 100);

        graphView = (GraphView) findViewById(R.id.graph);
        // activate horizontal zooming and scrolling
        graphView.getViewport().setScalable(true);
        // sadly, that's butt-ugly
        //graphView.getViewport().setScalableY(true);
        graphView.getViewport().setScrollable(true);
        graphView.getViewport().setXAxisBoundsManual(true);
        graphView.getViewport().setMinX(0);
        graphView.getViewport().setMaxX(graphViewPortWidth);
        graphView.getViewport().setYAxisBoundsManual(true);
        graphView.getViewport().setMinY(0);
        graphView.getViewport().setMaxY(graphMaxHR);
        graphView.getGridLabelRenderer().setNumVerticalLabels(5); // 5 is the magic number that works reliably...
        graphView.addSeries(a1Series);
        graphView.addSeries(a125Series);
        graphView.addSeries(a1125Series);
        graphView.addSeries(a1175Series);
        graphView.addSeries(a1HRVvt1Series);
        graphView.addSeries(a1HRVvt2Series);
        graphView.addSeries(hrSeries);
        graphView.addSeries(a1V2Series);
        // REQUIRED
        graphView.getSecondScale().addSeries(artifactSeries);
        graphView.getSecondScale().setMaxY(10);
        graphView.getSecondScale().setMinY(0);
        a1Series.setColor(Color.GREEN);
        a1Series.setThickness(5);
        a1V2Series.setColor(Color.MAGENTA);
        a1V2Series.setThickness(5);
        a1HRVvt1Series.setColor(getResources().getColor(R.color.colorHRVvt1));
        a1HRVvt2Series.setColor(getResources().getColor(R.color.colorHRVvt2));
        a125Series.setColor(Color.GRAY);
        a1175Series.setColor(Color.GRAY);
        a1125Series.setColor(Color.GRAY);
        a125Series.setThickness(1);
        a1125Series.setThickness(1);
        a1175Series.setThickness(1);
        hrSeries.setColor(Color.RED);
        artifactSeries.setColor(Color.BLUE);
        // yellow is a lot less visible that red
        a1HRVvt1Series.setThickness(6);
        // red is a lot more visible than yellow
        a1HRVvt2Series.setThickness(2);
        a1HRVvt1Series.appendData(new DataPoint(0, alpha1HRVvt1 * 100), false, maxDataPoints);
        a1HRVvt2Series.appendData(new DataPoint(0,alpha1HRVvt2 * 100), false, maxDataPoints);
        a125Series.appendData(new DataPoint(0,25), false, maxDataPoints);
        a1125Series.appendData(new DataPoint(0,125), false, maxDataPoints);
        a1175Series.appendData(new DataPoint(0,175), false, maxDataPoints);
        a1HRVvt1Series.appendData(new DataPoint(graphViewPortWidth,alpha1HRVvt1 * 100), false, maxDataPoints);
        a1HRVvt2Series.appendData(new DataPoint(graphViewPortWidth,alpha1HRVvt2 * 100), false, maxDataPoints);
        a125Series.appendData(new DataPoint(graphViewPortWidth,25), false, maxDataPoints);
        a1125Series.appendData(new DataPoint(graphViewPortWidth,125), false, maxDataPoints);
        a1175Series.appendData(new DataPoint(graphViewPortWidth,175), false, maxDataPoints);

        //hrvSeries.setColor(Color.BLUE);

        //setContentView(R.layout.activity_settings);
        Log.d(TAG, "Settings...");
        text_view.setText("Settings");
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, MySettingsFragment.class, null)
                .commit();

        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::MyWakelockTag");

        api.setApiCallback(new PolarBleApiCallback() {

                @Override
                public void blePowerStateChanged(boolean powered) {
                    Log.d(TAG, "BLE power: " + powered);
                    text_view.setText("BLE power "+ powered);
                }

                @Override
                public void deviceConnected(@NonNull PolarDeviceInfo polarDeviceInfo) {
                    quitSearchForPolarDevices();
                    Log.d(TAG, "Polar device CONNECTED: " + polarDeviceInfo.deviceId);
                    text_view.setText("Connected to "+polarDeviceInfo.deviceId);
                    Toast.makeText(getBaseContext(), "Connected to "+polarDeviceInfo.deviceId, Toast.LENGTH_SHORT).show();
                    // if no default device, store this one
                    DEVICE_ID = sharedPreferences.getString("polarDeviceID","");
                    if (DEVICE_ID.length()==0) {
                        Log.d(TAG,"Setting default device "+polarDeviceInfo.deviceId);
                        text_view.setText("Setting default device "+ polarDeviceInfo.deviceId);
                        sharedPreferences.edit().putString("polarDeviceID", polarDeviceInfo.deviceId).commit();
                    }
                }

                @Override
                public void deviceConnecting(@NonNull PolarDeviceInfo polarDeviceInfo) {
                    Log.d(TAG, "Polar device CONNECTING: " + polarDeviceInfo.deviceId);
                    text_view.setText("Connecting to "+polarDeviceInfo.deviceId);
                }

                @Override
                public void deviceDisconnected(@NonNull PolarDeviceInfo polarDeviceInfo) {
                    Log.d(TAG, "DISCONNECTED: " + polarDeviceInfo.deviceId);
                    text_view.setText("Disconnected from "+polarDeviceInfo.deviceId);
                    ecgDisposable = null;
                    accDisposable = null;
                    gyrDisposable = null;
                    magDisposable = null;
                    ppgDisposable = null;
                    ppiDisposable = null;
                    searchForPolarDevices();
                }

                @Override
                public void streamingFeaturesReady(@NonNull final String identifier,
                                                   @NonNull final Set<PolarBleApi.DeviceStreamingFeature> features) {
                    for (PolarBleApi.DeviceStreamingFeature feature : features) {
                        Log.d(TAG, "Streaming feature " + feature.toString() + " is ready");
                        text_view.setText("Streaming feature " + feature.toString() + " is ready");
                    }
                }

                @Override
                public void hrFeatureReady(@NonNull String identifier) {
                    Log.d(TAG, "HR READY: " + identifier);
                    text_view.setText("HR feature " + identifier + " is ready");
                    // hr notifications are about to start
                }

                @Override
                public void disInformationReceived(@NonNull String identifier, @NonNull UUID uuid, @NonNull String value) {
                    Log.d(TAG, "uuid: " + uuid + " value: " + value);
                }

                @Override
                public void batteryLevelReceived(@NonNull String identifier, int level) {
                    batteryLevel = level;
                    Log.d(TAG, "BATTERY LEVEL: " + level);
                }

                //NotificationManagerCompat notificationManager = NotificationManagerCompat.from(thisContext);

                // FIXME: this is a makeshift main event & timer loop
                @Override
                public void hrNotificationReceived(@NonNull String identifier, @NonNull PolarHrData data) {
                    currentTimeMS = System.currentTimeMillis();
                    if (!started) {
                        Log.d(TAG, "hrNotificationReceived: started!");
                        started = true;
                        starting = true;
                        thisIsFirstSample = true;
                        firstSampleMS = currentTimeMS;
                        // FIXME: why does the scroller not start with the top visible?
                        scrollView.scrollTo(0,0);
                    }
                    elapsedMS = (currentTimeMS - firstSampleMS);
                    elapsedSeconds = elapsedMS / 1000;
                    Log.d(TAG, "hrNotificationReceived cur "+currentTimeMS+" elapsed "+elapsedMS);
                    //if (elapsedSeconds > rrWindowSizeSeconds) {
                        updateTrackedFeatures(data);
                    //}
                }

                @Override
                    public void polarFtpFeatureReady(@NonNull String s) {
                        Log.d(TAG, "FTP ready");
                    }
                });

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && savedInstanceState == null) {
                    this.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                }

                searchForPolarDevices();

                // TODO: CHECK: is this safe or do we have to wait for some other setup tasks to finish...?
                tryPolarConnect();
    }

    private int getNrSamples() {
        int nrSamples = (newestSample < oldestSample) ? (newestSample + maxrrs - oldestSample) : (newestSample - oldestSample);
        return nrSamples;
    }

    // extract feature window from circular buffer (ugh), allowing for sample buffer after end of feature window
    // FIXME: requires invariants
    public double[] copySamplesFeatureWindow() {
        int next = 0;
        // rewind just past sample buffer
        int newestSampleIndex = (newestSample - sampleBufferMarginSec) % rrInterval.length;
        long newestTimestamp = rrIntervalTimestamp[newestSampleIndex];
        // rewind by the size of the window in seconds
        int oldestSampleIndex = newestSampleIndex;
        while (rrIntervalTimestamp[oldestSampleIndex] > (newestTimestamp - rrWindowSizeSec)) {
            oldestSampleIndex = (oldestSampleIndex - 1) % rrInterval.length;
        }
        return copySamplesRange(oldestSampleIndex,newestSampleIndex);
    }

    public double[] copySamplesAll() {
        return copySamplesRange(oldestSample, newestSample);
    }

    public double[] copySamplesRange(int oldest, int newest) {
        double[] result = new double[getNrSamples()];
        int next = 0;
        // FIXME: unverified
        for (int i = oldest; i != newest; i = (i + 1) % rrInterval.length) {
            result[next] = rrInterval[i];
            next++;
        }
        return result;
    }

    private int getNrArtifacts() {
        int result = 0;
        for (int i = oldestArtifactSample; i != newestArtifactSample; i = (i + 1) % artifactTimestamp.length) {
            result++;
        }
        return result;
    }

    private void updateTrackedFeatures(@NotNull PolarHrData data) {
        wakeLock.acquire();
        Log.d(TAG, "updateTrackedFeatures");
        experimental = sharedPreferences.getBoolean("experimental", false);
        if (sharedPreferences.getBoolean("keepScreenOn", false)) {
            text_view.setText("Keep screen on");
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            text_view.setText("Don't keep screen on");
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        currentHR = data.hr;

        String artifactCorrectionThresholdSetting = sharedPreferences.getString("artifactThreshold", "Auto");
        if (artifactCorrectionThresholdSetting.equals("Auto")) {
            if (data.hr>95) {
                exerciseMode = "Workout";
                artifactCorrectionThreshold = 0.05;
            } else if (data.hr<80) {
                exerciseMode = "Light";
                artifactCorrectionThreshold = 0.25;
            }
        } else if (artifactCorrectionThresholdSetting.equals("0.25")) {
            exerciseMode = "Light";
            artifactCorrectionThreshold = 0.25;
        } else {
            exerciseMode = "Workout";
            artifactCorrectionThreshold = 0.05;
        }

        String notificationDetailSetting = sharedPreferences.getString("notificationDetail", "full");

        String alpha1EvalPeriodSetting =  sharedPreferences.getString("alpha1CalcPeriod", "20");
        try {
            alpha1EvalPeriod = Integer.parseInt(alpha1EvalPeriodSetting);
        } catch (final NumberFormatException e) {
            Log.d(TAG,"Number format exception alpha1EvalPeriod "+alpha1EvalPeriodSetting+" "+e.toString());
            alpha1EvalPeriod = 20;
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString("alpha1CalcPeriod", "20");
            editor.apply();
            Log.d(TAG, "alpha1CalcPeriod wrote "+sharedPreferences.getString("alpha1CalcPeriod", "??"));
        }
        if (alpha1EvalPeriod<5) {
            Log.d(TAG,"alpha1EvalPeriod<5");
            alpha1EvalPeriod = 5;
            sharedPreferences.edit().putString("alpha1CalcPeriod", "5").commit();
        }
        long timestamp = currentTimeMS;
        for (int rr : data.rrsMs) {
            String msg = "" + timestamp + "," + rr + "," + logRRelapsedMS;
            writeLogFiles(msg, rrLogStreamNew, rrLogStreamLegacy, "rr");
            logRRelapsedMS += rr;
            timestamp += rr;
        }
        //
        // FILTERING / RECORDING RR intervals
        //
        String rejected = "";
        boolean haveArtifacts = false;
        List<Integer> rrsMs = data.rrsMs;
//        double lowerBound = rrMeanWindowed * (1 - artifactCorrectionThreshold);
//        double upperBound = rrMeanWindowed * (1 + artifactCorrectionThreshold);
        for (int si = 0; si < data.rrsMs.size(); si++) {
            double newrr = data.rrsMs.get(si);
            double lowerBound = prevrr * (1 - artifactCorrectionThreshold);
            double upperBound = prevrr * (1 + artifactCorrectionThreshold);
//            lowerBound = rrMeanWindowed * (1 - artifactCorrectionThreshold);
//            upperBound = rrMeanWindowed * (1 + artifactCorrectionThreshold);
            Log.d(TAG, "prevrr " + prevrr + " lowerBound " + lowerBound + " upperBound " + upperBound);
            if (thisIsFirstSample || lowerBound < newrr && newrr < upperBound) {
                Log.d(TAG, "accept " + newrr);
                // if in_RRs[(i-1)]*(1-artifact_correction_threshold) < in_RRs[i] < in_RRs[(i-1)]*(1+artifact_correction_threshold):
                rrInterval[newestSample] = newrr;
                rrIntervalTimestamp[newestSample] = currentTimeMS;
                newestSample = (newestSample + 1) % maxrrs;
                thisIsFirstSample = false;
            } else {
                Log.d(TAG, "drop...");
                artifactTimestamp[newestArtifactSample] = currentTimeMS;
                newestArtifactSample = (newestArtifactSample + 1) % maxrrs;
                Log.d(TAG, "reject artifact " + newrr);
                rejected += "" + newrr;
                haveArtifacts = true;
                totalRejected++;
            }
            prevrr = newrr;
        }
        String rejMsg = haveArtifacts ? (", Rejected: " + rejected) : "";
        Log.d(TAG, "Polar device HR value: " + data.hr + " rrsMs: " + data.rrsMs + " rr: " + data.rrs + " contact: " + data.contactStatus + "," + data.contactStatusSupported + " " + rejMsg);
        int expired = 0;
        // expire old samples
        Log.d(TAG, "Expire old RRs");
        while (rrIntervalTimestamp[oldestSample] < currentTimeMS - rrWindowSizeSec * 1000) {
            oldestSample = (oldestSample + 1) % maxrrs;
            expired++;
        }
        Log.d(TAG, "Expire old artifacts");
        while (oldestArtifactSample != newestArtifactSample && artifactTimestamp[oldestArtifactSample] < currentTimeMS - rrWindowSizeSec * 1000) {
            Log.d(TAG, "Expire at " + oldestArtifactSample);
            oldestArtifactSample = (oldestArtifactSample + 1) % maxrrs;
        }
        Log.d(TAG, "elapsedMS " + elapsedMS);
        //
        long absSeconds = Math.abs(elapsedSeconds);
        String positive = String.format(
                "%02d:%02d:%02d",
                absSeconds / 3600,
                (absSeconds % 3600) / 60,
                absSeconds % 60);
        //text_time.setText(mode + "    " +positive + "    \uD83D\uDD0B"+batteryLevel);
        text_mode.setText(exerciseMode);
        text_time.setText(positive);
        text_batt.setText("\uD83D\uDD0B"+batteryLevel);

        //
        // Automatic beat correction
        // https://www.kubios.com/hrv-preprocessing/
        //
        long elapsed = elapsedMS / 1000;
        int nrSamples = getNrSamples();
        int nrArtifacts = getNrArtifacts();
        // get full window (c. 220sec)
        double[] allSamples = copySamplesAll();
        // get sample window (c. 120sec)
        double[] featureWindowSamples;
        double[] samples = allSamples;
//        if (elapsedSeconds > rrWindowSizeSeconds) {
//            featureWindowSamples = copySamplesFeatureWindow();
//            samples = featureWindowSamples;
//        }
        Log.d(TAG, "Samples: " + v_toString(samples));
        double[] dRR = v_differential(samples);
        //Log.d(TAG, "dRR: " + v_toString(dRR));

        //
        // FEATURES
        //
        rmssdWindowed = getRMSSD(samples);
        // TODO: CHECK: avg HR == 60 * 1000 / (mean of observed filtered(?!) RRs)
        rrMeanWindowed = v_mean(samples);
        Log.d(TAG,"rrMeanWindowed "+rrMeanWindowed);
        hrMeanWindowed = round(60 * 1000 * 100 / rrMeanWindowed) / 100.0;
        Log.d(TAG,"hrMeanWindowed "+hrMeanWindowed);
        // Periodic actions: check alpha1 and issue voice update
        // - skip one period's worth after first HR update
        // - only within the first two seconds of this period window
        // - only when at least three seconds have elapsed since last invocation
        // FIXME: what precisely is required for alpha1 to be well-defined?
        // FIXME: The prev_a1_check now seems redundant
        Log.d(TAG,"Elapsed "+elapsed+" currentTimeMS "+currentTimeMS+ " a1evalPeriod "+alpha1EvalPeriod+" prevA1Timestamp "+prevA1Timestamp);
        if ((elapsed > alpha1EvalPeriod) && (elapsed % alpha1EvalPeriod <= 2) && (currentTimeMS > prevA1Timestamp + 3000)) {
            Log.d(TAG,"alpha1...");
            alpha1Windowed = dfaAlpha1V1(samples, 2, 4, 30, false);
            alpha1RoundedWindowed = round(alpha1Windowed * 100) / 100.0;
            if (experimental) {
                alpha1WindowedV2 = dfaAlpha1V2(samples, 2, 4, 30);
                alpha1RoundedWindowedV2 = round(alpha1WindowedV2 * 100) / 100.0;
            }
            prevA1Timestamp = currentTimeMS;
            writeLogFiles("" + timestamp
                    + "," + hrMeanWindowed
                    + "," + rmssdWindowed
                    + ","
                    + "," + alpha1RoundedWindowed
                    + "," + nrArtifacts
                    + "," + nrSamples
                    + "," + artifactsPercentWindowed
                    + "," + artifactCorrectionThreshold
                    + "," + alpha1RoundedWindowedV2
                    ,
                    featureLogStreamNew,
                    featureLogStreamLegacy,
                    "features");
            if (sharedPreferences.getBoolean("notificationsEnabled", true)) {
                Log.d(TAG,"Feature notification...");
                // https://stackoverflow.com/questions/5502427/resume-application-and-stack-from-notification
                final Intent notificationIntent = new Intent(this, MainActivity.class);
                notificationIntent.setAction(Intent.ACTION_MAIN);
                notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
                //notification.setContentIntent(pendingIntent)

                uiNotificationBuilder = new NotificationCompat.Builder(this, UI_CHANNEL_ID)
                        .setOngoing(true)
                        .setSmallIcon(R.mipmap.ic_launcher_foreground)
                        .setPriority(NotificationManager.IMPORTANCE_HIGH)
                        .setCategory(Notification.CATEGORY_MESSAGE)
                        .setContentIntent(pendingIntent)
                        .setContentTitle("a1 " + alpha1RoundedWindowed +" drop "+artifactsPercentWindowed+"%");
                if (notificationDetailSetting.equals("full")) {
                    uiNotificationBuilder.setContentText("HR " +currentHR+  " batt " + batteryLevel + "% rmssd " + rmssdWindowed);
                } else if (notificationDetailSetting.equals("titleHR")) {
                    uiNotificationBuilder.setContentText("HR " +currentHR);
                }
                uiNotificationManager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, uiNotificationBuilder.build());
            }
        }

        //
        // DISPLAY // AUDIO // LOGGING
        //
        if (haveArtifacts && sharedPreferences.getBoolean(AUDIO_OUTPUT_ENABLED, false)) {
            //spokenOutput("drop");
            mp.start();
        }
        StringBuilder logmsg = new StringBuilder();
        logmsg.append(elapsed + "s");
        logmsg.append(", rrsMs: " + data.rrsMs);
        logmsg.append(rejMsg);
        if (experimental) {
            logmsg.append(", a1V2 " + alpha1RoundedWindowedV2);
        }
        logmsg.append(", total rejected: " + totalRejected);
        String logstring = logmsg.toString();

        artifactsPercentWindowed = (int) round(nrArtifacts * 100 / (double) nrSamples);
        text_artifacts.setText("" + nrArtifacts + "/" + nrSamples + " (" + artifactsPercentWindowed + "%) ["+artifactCorrectionThreshold+"]");
        if (haveArtifacts) {
            text_artifacts.setBackgroundResource(R.color.colorHighlight);
        } else {
            text_artifacts.setBackgroundResource(R.color.colorBackground);
        }
        text_view.setText(logstring);
        text_hr.setText("" + data.hr);
        text_hrv.setText("" + round(rmssdWindowed));
        text_a1.setText("" + alpha1RoundedWindowed);
        // configurable top-of-optimal threshold for alpha1
        double alpha1MaxOptimal = Double.parseDouble(sharedPreferences.getString("alpha1MaxOptimal", "1.0"));
        // wait for run-in period
        if (elapsed > 30) {
            if (alpha1RoundedWindowed < alpha1HRVvt2) {
                text_a1.setBackgroundResource(R.color.colorMaxIntensity);
            } else if (alpha1RoundedWindowed < alpha1HRVvt1) {
                text_a1.setBackgroundResource(R.color.colorMedIntensity);
            } else if (alpha1RoundedWindowed < alpha1MaxOptimal) {
                text_a1.setBackgroundResource(R.color.colorFatMaxIntensity);
            } else {
                text_a1.setBackgroundResource(R.color.colorEasyIntensity);
            }
        }
        double elapsedMin = elapsed / 60.0;
        double tenSecAsMin = 1.0 / 6.0;
        boolean scrollToEnd = (elapsedMin > (graphViewPortWidth - tenSecAsMin)) && (elapsed % 10 == 0);
        hrSeries.appendData(new DataPoint(elapsedMin, data.hr), scrollToEnd, maxDataPoints);
        a1Series.appendData(new DataPoint(elapsedMin, alpha1Windowed * 100.0), scrollToEnd, maxDataPoints);
        if (experimental) {
            a1V2Series.appendData(new DataPoint(elapsedMin, alpha1WindowedV2 * 100.0), scrollToEnd, maxDataPoints);
        }
        if (scrollToEnd) {
            double nextX = elapsedMin + tenSecAsMin;
            a1HRVvt1Series.appendData(new DataPoint(nextX, 75), scrollToEnd, maxDataPoints);
            a1HRVvt2Series.appendData(new DataPoint(nextX, 50), scrollToEnd, maxDataPoints);
            a125Series.appendData(new DataPoint(nextX, 25), scrollToEnd, maxDataPoints);
            a1125Series.appendData(new DataPoint(nextX, 125), scrollToEnd, maxDataPoints);
            a1175Series.appendData(new DataPoint(nextX, 175), scrollToEnd, maxDataPoints);
        }
        artifactSeries.appendData(new DataPoint(elapsedMin, artifactsPercentWindowed), scrollToEnd, maxDataPoints);

        Log.d(TAG, data.hr + " " + alpha1RoundedWindowed + " " + rmssdWindowed);
        Log.d(TAG, logstring);
        Log.d(TAG, "Elapsed % alpha1EvalPeriod" + (elapsed % alpha1EvalPeriod));
        audioUpdate(data, currentTimeMS);
        starting = false;
        wakeLock.release();
    }

    private void tryPolarConnect(String tmpDeviceID) {
//        quitSearchForPolarDevices();
        Log.d(TAG,"tryPolarConnect to "+tmpDeviceID);
        try {
            text_view.setText("Trying to connect to: " + tmpDeviceID);
            api.connectToDevice(tmpDeviceID);
        } catch (PolarInvalidArgument polarInvalidArgument) {
            text_view.setText("PolarInvalidArgument: " + polarInvalidArgument);
            polarInvalidArgument.printStackTrace();
        }
    }

    private void tryPolarConnect() {
        //quitSearchForPolarDevices();
        Log.d(TAG,"tryPolarConnect");
        DEVICE_ID = sharedPreferences.getString("polarDeviceID","");
        if (DEVICE_ID.length()>0) {
            try {
                text_view.setText("Trying to connect to: " + DEVICE_ID);
                api.connectToDevice(DEVICE_ID);
            } catch (PolarInvalidArgument polarInvalidArgument) {
                text_view.setText("PolarInvalidArgument: " + polarInvalidArgument);
                polarInvalidArgument.printStackTrace();
            }
        } else {
            text_view.setText("No device ID set");
        }
    }

    private void writeLogFiles(String msg, FileWriter logStream, FileWriter logStream2, String tag) {
        writeLogFile(msg,logStream,tag);
        writeLogFile(msg,logStream2,tag);
    }

    private void writeLogFile(String msg, FileWriter logStream, String tag) {
        try {
            logStream.append(msg+"\n");
            logStream.flush();
            // avoid feedback loop through the local Log mechanism
            //android.util.Log.d(TAG,"Wrote to "+tag+" log: "+msg);
        } catch (IOException e) {
            android.util.Log.d(TAG,"IOException writing to "+tag+" log");
            text_view.setText("IOException writing to "+tag+" log");
            e.printStackTrace();
        }
    }

    private FileWriter createLogFile(String tag) {
        FileWriter logStream = null;
        try {
//            File dir = new File(getApplicationContext().getFilesDir(), "FatMaxOptimizer");
//            File dir = new File(getApplicationContext().getExternalFilesDir(null), "FatMaxOptimizer");
            String dateString = getDate(System.currentTimeMillis(), "yyyyMMdd_HHmmss");
            File file = new File(getApplicationContext().getExternalFilesDir(null), "/FatMaxOptimiser."+dateString+"."+tag+".csv");
            logStream = new FileWriter(file);
            Log.d(TAG,"Logging RRs to "+file.getAbsolutePath());
        } catch (FileNotFoundException e) {
            text_view.setText("FileNotFoundException");
            Log.d(TAG,"FileNotFoundException");
            e.printStackTrace();
        } catch (IOException e) {
            text_view.setText("IOException creating log file");
            Log.d(TAG,"IOException creating log file");
            e.printStackTrace();
        }
        return logStream;
    }

    Map<String,File> currentLogFiles = new HashMap<String,File>();

    private FileWriter createLogFileNew(String tag, String extension) {
        FileWriter logStream = null;
        try {
//            File dir = new File(getApplicationContext().getFilesDir(), "FatMaxOptimizer");
//            File dir = new File(getApplicationContext().getExternalFilesDir(null), "FatMaxOptimizer");
            String dateString = getDate(System.currentTimeMillis(), "yyyyMMdd_HHmmss");
            File privateRootDir = getFilesDir();
            privateRootDir.mkdir();
            File logsDir = new File(privateRootDir, "logs");
            logsDir.mkdir();
            //File file = new File(getApplicationContext().getExternalFilesDir(null), "/FatMaxOptimiser."+dateString+"."+tag+".csv");
            File file = new File(logsDir, "/FatMaxOptimiser."+dateString+"."+tag+"."+extension);
            // Get the files/images subdirectory;
            logStream = new FileWriter(file);
            Log.d(TAG,"Logging "+tag+" to "+file.getAbsolutePath());
            currentLogFiles.put(tag,file);
        } catch (FileNotFoundException e) {
                text_view.setText("FileNotFoundException");
                Log.d(TAG,"FileNotFoundException");
                e.printStackTrace();
        } catch (IOException e) {
            text_view.setText("IOException creating log file");
            Log.d(TAG,"IOException creating log file");
            e.printStackTrace();
        }
        return logStream;
    }

    private double getRMSSD(double[] samples) {
        double[] NNdiff = v_abs(v_differential(samples));
        //rmssd = round(np.sqrt(np.sum((NNdiff * NNdiff) / len(NNdiff))), 2)
        double rmssd = sqrt(v_sum(v_power_s2(NNdiff,2)) / NNdiff.length);
        return round(rmssd * 100) / 100.0;
    }

    // pre: no artifacts(?)
    private void testRMSSD_1() {
        Log.d(TAG,"testRMSSD_1 ...");
        double result = getRMSSD(samples1);
        Log.d(TAG,"testRMSSD_1 done");
    }

    // determine whether to update, and what content, to provide via audio/notification
    private void audioUpdate(@NotNull PolarHrData data, long currentTime_ms){
            long timeSinceLastSpokenUpdate_s = (long) (currentTime_ms - prevSpokenUpdateMS) / 1000;
            //long timeSinceLastSpokenArtifactsUpdate_s = (long) (currentTime_ms - prevSpokenArtifactsUpdateMS) / 1000;

            double a1 = alpha1RoundedWindowed;
            int rmssd = (int) round(rmssdWindowed);
            int minUpdateWaitSeconds = Integer.parseInt(sharedPreferences.getString("minUpdateWaitSeconds", "15"));
            int maxUpdateWaitSeconds = Integer.parseInt(sharedPreferences.getString("maxUpdateWaitSeconds", "60"));
            // something like your MAF --- close to your max training HR
            int upperOptimalHRthreshold = Integer.parseInt(sharedPreferences.getString("upperOptimalHRthreshold", "130"));
            int upperRestingHRthreshold = Integer.parseInt(sharedPreferences.getString("upperRestingHRthreshold", "90"));
            double artifactsRateAlarmThreshold = Double.parseDouble(sharedPreferences.getString("artifactsRateAlarmThreshold", "5"));
            double upperOptimalAlpha1Threshold = Double.parseDouble(sharedPreferences.getString("upperOptimalAlpha1Threshold", "1.0"));
            double lowerOptimalAlpha1Threshold = Double.parseDouble(sharedPreferences.getString("upperOptimalAlpha1Threshold", "0.85"));
            String artifactsUpdate = "";
            String featuresUpdate = "";
            if (elapsedSeconds>30 && timeSinceLastSpokenUpdate_s > minUpdateWaitSeconds) {
                if (artifactsPercentWindowed > 0) {
                    artifactsUpdate = "dropped " + artifactsPercentWindowed + " percent";
                }
                // lower end of optimal alph1 - close to overtraining - frequent updates, prioritise a1, abbreviated
                if (data.hr > upperOptimalHRthreshold || a1 < lowerOptimalAlpha1Threshold) {
                    featuresUpdate = alpha1RoundedWindowed + " " + data.hr;
                // higher end of optimal - prioritise a1, close to undertraining?
                } else if ((data.hr > (upperOptimalHRthreshold - 10) || alpha1RoundedWindowed < upperOptimalAlpha1Threshold)) {
                    featuresUpdate =  "Alpha one, " + alpha1RoundedWindowed + " heart rate " + data.hr;
                // lower end of optimal - prioritise a1
                } else if (artifactsPercentWindowed > artifactsRateAlarmThreshold ||
                    data.hr > upperRestingHRthreshold && timeSinceLastSpokenUpdate_s >= maxUpdateWaitSeconds) {
                    featuresUpdate = "Alpha one " +alpha1RoundedWindowed + " heart rate "+ data.hr;
                // warm up / cool down --- low priority, update RMSSD instead of alpha1
                } else if (artifactsPercentWindowed > artifactsRateAlarmThreshold ||
                        timeSinceLastSpokenUpdate_s >= maxUpdateWaitSeconds) {
                    featuresUpdate = "Heart rate " + data.hr + ". HRV " + rmssd;
                }
            }
            if (featuresUpdate.length() > 0) {
                prevSpokenUpdateMS = currentTime_ms;
                if (artifactsPercentWindowed > artifactsRateAlarmThreshold) {
                    nonScreenUpdate(artifactsUpdate + " " + featuresUpdate);
                } else {
                    nonScreenUpdate(featuresUpdate + ", " + artifactsUpdate);
                }
            }
        }

        // Update the user via audio / notification, if enabled
        private void nonScreenUpdate(String update) {
            if (sharedPreferences.getBoolean(AUDIO_OUTPUT_ENABLED, false)) {
                ttobj.speak(update, TextToSpeech.QUEUE_FLUSH, null);
            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
            text_view.setText("Permission update: "+requestCode);
            if (requestCode == 1) {
                Log.d(TAG, "bt ready");
            }
        }

        @Override
        public void onPause() {
            text_view.setText("Paused");
            super.onPause();
            api.backgroundEntered();
        }

        @Override
        public void onResume() {
            text_view.setText("Resumed");
            super.onResume();
            api.foregroundEntered();
        }

        @Override
        public void onDestroy() {
            text_view.setText("Destroyed");
            Toast.makeText(this, "FatMaxxer stopped", Toast.LENGTH_SHORT).show();
            super.onDestroy();
            try {
                rrLogStreamNew.close();
            } catch (IOException e) {
                text_view.setText("IOException "+e.toString());
                e.printStackTrace();
            }
            doUnbindService();

            Intent i = new Intent(MainActivity.this, LocalService.class);
            i.setAction("STOP");
            Log.d(TAG,"intent to stop local service "+i);
            MainActivity.this.stopService(i);

            api.shutDown();
        }
}
