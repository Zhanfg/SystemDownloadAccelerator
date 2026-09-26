package com.v4atune.app;

import android.os.IBinder;
import android.os.Parcel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.zip.CRC32;

final class ViperLiveControl {
    private static final String SERVICE = "viper.control";
    private static final String DESCRIPTOR = "viper.fx.IViperControl";
    private static final int TX_DISPATCH = IBinder.FIRST_CALL_TRANSACTION;

    private static final int TYPE_BOOL = 1;
    private static final int TYPE_INT = 2;
    private static final int TYPE_FLOAT = 3;
    private static final int TYPE_FLOAT_ARRAY = 4;
    private static final int TYPE_BYTES = 5;
    private static final int TYPE_INT_ARRAY = 6;
    private static final int NO_INDEX = -1;

    private ViperLiveControl() {}

    static boolean available() {
        try {
            return service() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static void applyProfile(JSONObject root) throws Exception {
        // Scalars.
        JSONObject g;

        g=root.getJSONObject("masterLimiter");
        f(0x10110,g,"threshold"); f(0x10111,g,"outputVolume"); f(0x10112,g,"channelPan");

        g=root.getJSONObject("playbackGainControl");
        b(0x10120,g,"enable"); f(0x10121,g,"strength"); f(0x10122,g,"maxGain"); f(0x10123,g,"outputThreshold");

        g=root.getJSONObject("lufs");
        b(0x10130,g,"enable"); f(0x10131,g,"target"); f(0x10132,g,"maxGain"); i(0x10133,g,"speed");

        g=root.getJSONObject("fetCompressor");
        b(0x10140,g,"enable"); f(0x10141,g,"threshold"); f(0x10142,g,"ratio");
        f(0x10143,g,"knee"); b(0x10144,g,"kneeAuto"); f(0x10145,g,"gain"); b(0x10146,g,"gainAuto");
        f(0x10147,g,"attack"); b(0x10148,g,"attackAuto"); f(0x10149,g,"release"); b(0x1014A,g,"releaseAuto");
        f(0x1014B,g,"kneeMulti"); f(0x1014C,g,"maxAttack"); f(0x1014D,g,"maxRelease");
        f(0x1014E,g,"crest"); f(0x1014F,g,"adapt"); b(0x10150,g,"noClip");

        g=root.getJSONObject("bass");
        b(0x10160,g,"enable"); i(0x10161,g,"mode"); i(0x10162,g,"frequency"); f(0x10163,g,"gain"); b(0x10164,g,"antiPop");

        g=root.getJSONObject("bassMono");
        b(0x10170,g,"enable"); i(0x10171,g,"mode"); i(0x10172,g,"frequency"); f(0x10173,g,"gain"); b(0x10174,g,"antiPop");

        g=root.getJSONObject("psychoacousticBass");
        b(0x10180,g,"enable"); i(0x10181,g,"cutoff"); f(0x10182,g,"intensity"); i(0x10183,g,"harmonicOrder"); f(0x10184,g,"originalLevel");

        g=root.getJSONObject("spectrumExtension");
        b(0x10190,g,"enable"); i(0x10191,g,"strength"); f(0x10192,g,"exciter");

        g=root.getJSONObject("equalizer");
        b(0x101A0,g,"enable"); i(0x101A2,g,"bandCount");
        JSONArray eq=g.getJSONArray("bands");
        float[] eqf=new float[eq.length()];
        for(int n=0;n<eq.length();n++) eqf[n]=(float)eq.getDouble(n);
        dispatch(0x101A3,payloadFloats(eqf,NO_INDEX));

        g=root.getJSONObject("convolver");
        b(0x101B0,g,"enable"); f(0x101B5,g,"crossChannel");

        g=root.getJSONObject("ddc"); b(0x101C0,g,"enable");

        g=root.getJSONObject("fieldSurround");
        b(0x101D0,g,"enable"); f(0x101D1,g,"widening"); f(0x101D2,g,"midImage"); i(0x101D3,g,"depth");

        g=root.getJSONObject("diffSurround");
        b(0x101E0,g,"enable"); f(0x101E1,g,"delay"); b(0x101E2,g,"reverse"); f(0x101E3,g,"wetDryMix"); i(0x101E4,g,"lpCutoff");

        g=root.getJSONObject("stereoImager");
        b(0x101F0,g,"enable"); f(0x101F1,g,"lowWidth"); f(0x101F2,g,"midWidth"); f(0x101F3,g,"highWidth");
        i(0x101F4,g,"lowCrossover"); i(0x101F5,g,"highCrossover");

        g=root.getJSONObject("headphoneSurround");
        b(0x10200,g,"enable"); i(0x10201,g,"quality");

        g=root.getJSONObject("reverb");
        b(0x10210,g,"enable"); f(0x10211,g,"roomSize"); f(0x10212,g,"width"); f(0x10213,g,"damp"); f(0x10214,g,"wet"); f(0x10215,g,"dry");

        g=root.getJSONObject("dynamicSystem");
        b(0x10220,g,"enable"); i(0x10221,g,"xLow"); i(0x10222,g,"xHigh"); i(0x10223,g,"yLow"); i(0x10224,g,"yHigh");
        f(0x10225,g,"sideGainLow"); f(0x10226,g,"sideGainHigh"); f(0x10227,g,"strength");

        g=root.getJSONObject("clarity");
        b(0x10230,g,"enable"); i(0x10231,g,"mode"); f(0x10232,g,"gain");

        g=root.getJSONObject("cure");
        b(0x10240,g,"enable"); i(0x10241,g,"crossfeedPreset");

        g=root.getJSONObject("tubeSimulator"); b(0x10250,g,"enable");
        g=root.getJSONObject("analogX"); b(0x10260,g,"enable"); i(0x10261,g,"mode");
        g=root.getJSONObject("speakerCorrection"); b(0x10270,g,"enable");

        // Multi-band compressor list parameters are indexed, matching ViPER UI dispatch.
        g=root.getJSONObject("multibandCompressor");
        b(0x10280,g,"enable");
        dispatch(0x10281,payloadInt(5,NO_INDEX));
        indexedBool(0x10293,g.getJSONArray("bandEnables"));
        indexedInt(0x10282,g.getJSONArray("crossovers"));
        indexedFloat(0x10283,g.getJSONArray("thresholds"));
        indexedFloat(0x10284,g.getJSONArray("ratios"));
        indexedFloat(0x10285,g.getJSONArray("knees"));
        indexedBool(0x10286,g.getJSONArray("kneeAutos"));
        indexedFloat(0x10287,g.getJSONArray("gains"));
        indexedBool(0x10288,g.getJSONArray("gainAutos"));
        indexedFloat(0x10289,g.getJSONArray("attacks"));
        indexedBool(0x1028A,g.getJSONArray("attackAutos"));
        indexedFloat(0x1028B,g.getJSONArray("releases"));
        indexedBool(0x1028C,g.getJSONArray("releaseAutos"));
        indexedFloat(0x1028D,g.getJSONArray("kneeMultis"));
        indexedFloat(0x1028E,g.getJSONArray("maxAttacks"));
        indexedFloat(0x1028F,g.getJSONArray("maxReleases"));
        indexedFloat(0x10290,g.getJSONArray("crests"));
        indexedFloat(0x10291,g.getJSONArray("adapts"));
        indexedBool(0x10292,g.getJSONArray("noClips"));

        g=root.getJSONObject("dynamicEq");
        b(0x102A0,g,"enable"); i(0x102A1,g,"bandCount");
        indexedInt(0x102A2,g.getJSONArray("freqs"));
        indexedFloat(0x102A3,g.getJSONArray("qs"));
        indexedFloat(0x102A4,g.getJSONArray("gains"));
        indexedFloat(0x102A5,g.getJSONArray("thresholds"));
        indexedFloat(0x102A6,g.getJSONArray("attacks"));
        indexedFloat(0x102A7,g.getJSONArray("releases"));
        indexedInt(0x102A8,g.getJSONArray("filterTypes"));
    }

    static void streamConvolver(File wav) throws Exception {
        if (wav == null || !wav.isFile()) return;
        byte[] bytes=Files.readAllBytes(wav.toPath());
        if(bytes.length<44) throw new IllegalArgumentException("FIR WAV too small");
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if(b.getInt(0)!=0x46464952 || b.getInt(8)!=0x45564157) throw new IllegalArgumentException("Not RIFF/WAVE");
        int channels=1, bits=16, dataOff=-1, dataSize=-1;
        int pos=12;
        while(pos+8<=bytes.length){
            int id=b.getInt(pos), size=b.getInt(pos+4), payload=pos+8;
            if(id==0x20746D66 && size>=16){
                channels=b.getShort(payload+2)&0xffff;
                bits=b.getShort(payload+14)&0xffff;
            } else if(id==0x61746164){
                dataOff=payload; dataSize=Math.min(size,bytes.length-payload); break;
            }
            pos=payload+size+(size&1);
        }
        if(dataOff<0 || bits!=16) throw new IllegalArgumentException("Expected PCM16 FIR WAV");
        int samples=dataSize/2;
        float[] fs=new float[samples];
        for(int n=0;n<samples;n++) fs[n]=b.getShort(dataOff+n*2)/32768.0f;

        ByteBuffer raw=ByteBuffer.allocate(fs.length*4).order(ByteOrder.LITTLE_ENDIAN);
        for(float v:fs) raw.putFloat(v);
        CRC32 crc=new CRC32(); crc.update(raw.array());

        dispatch(0x101B2,payloadInts(new int[]{fs.length,channels,0}));
        int off=0;
        while(off<fs.length){
            int n=Math.min(2046,fs.length-off);
            float[] chunk=new float[n];
            System.arraycopy(fs,off,chunk,0,n);
            dispatch(0x101B3,payloadFloats(chunk,NO_INDEX));
            off+=n;
        }
        int kernelId=wav.getName().hashCode();
        dispatch(0x101B4,payloadInts(new int[]{fs.length,(int)crc.getValue(),kernelId}));
    }

    private static void b(int p,JSONObject g,String k)throws Exception{dispatch(p,payloadBool(g.getBoolean(k),NO_INDEX));}
    private static void i(int p,JSONObject g,String k)throws Exception{dispatch(p,payloadInt(g.getInt(k),NO_INDEX));}
    private static void f(int p,JSONObject g,String k)throws Exception{dispatch(p,payloadFloat((float)g.getDouble(k),NO_INDEX));}
    private static void indexedBool(int p,JSONArray a)throws Exception{for(int n=0;n<a.length();n++)dispatch(p,payloadBool(a.getBoolean(n),n));}
    private static void indexedInt(int p,JSONArray a)throws Exception{for(int n=0;n<a.length();n++)dispatch(p,payloadInt(a.getInt(n),n));}
    private static void indexedFloat(int p,JSONArray a)throws Exception{for(int n=0;n<a.length();n++)dispatch(p,payloadFloat((float)a.getDouble(n),n));}

    private static IBinder service() throws Exception {
        Class<?> sm=Class.forName("android.os.ServiceManager");
        return (IBinder)sm.getMethod("getService",String.class).invoke(null,SERVICE);
    }

    private static void dispatch(int param,byte[] value)throws Exception{
        IBinder binder=service();
        if(binder==null) throw new IllegalStateException("viper.control is unavailable");
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(param);
            data.writeByteArray(value);
            if(!binder.transact(TX_DISPATCH,data,reply,0)) throw new IllegalStateException("Binder transact failed for "+param);
            reply.readException();
        }finally{data.recycle();reply.recycle();}
    }

    private static byte[] payloadBool(boolean v,int idx){
        return ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN).putInt(TYPE_BOOL).putInt(idx).putInt(1).put((byte)(v?1:0)).array();
    }
    private static byte[] payloadInt(int v,int idx){
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(TYPE_INT).putInt(idx).putInt(1).putInt(v).array();
    }
    private static byte[] payloadFloat(float v,int idx){
        return ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(TYPE_FLOAT).putInt(idx).putInt(1).putFloat(v).array();
    }
    private static byte[] payloadFloats(float[] v,int idx){
        ByteBuffer b=ByteBuffer.allocate(12+v.length*4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(TYPE_FLOAT_ARRAY).putInt(idx).putInt(v.length);
        for(float x:v)b.putFloat(x);
        return b.array();
    }
    private static byte[] payloadInts(int[] v){
        ByteBuffer b=ByteBuffer.allocate(12+v.length*4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(TYPE_INT_ARRAY).putInt(NO_INDEX).putInt(v.length);
        for(int x:v)b.putInt(x);
        return b.array();
    }
}
