package id.poncol.tvcompanion.tuya;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

final class Credentials {
    private static SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias("lamp")) {
            KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("lamp",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)store.getKey("lamp",null);
    }
    static JSONObject validate(JSONObject json) throws Exception {
        Tuya35.address(json.getString("ip"));
        if(!"3.5".equals(json.getString("version")) || json.getString("key").getBytes(StandardCharsets.UTF_8).length!=16 || json.getString("id").isEmpty())throw new IOException("Invalid provisioning");
        return json;
    }
    static void save(Context context,JSONObject json) throws Exception {
        validate(json);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());
        byte[] plain=json.toString().getBytes(StandardCharsets.UTF_8);
        android.util.AtomicFile file=new android.util.AtomicFile(new File(context.getFilesDir(),"lamp.enc"));
        FileOutputStream out=file.startWrite();
        try{out.write(c.getIV());out.write(c.doFinal(plain));file.finishWrite(out);}
        catch(Exception e){file.failWrite(out);throw e;}finally{Arrays.fill(plain,(byte)0);}
    }
    static JSONObject load(Context context) throws Exception {
        File inbox=new File(context.getFilesDir(),"provision.json");
        if(inbox.exists()) {
            try(DataInputStream in=new DataInputStream(new FileInputStream(inbox))) {
                if(inbox.length()>4096)throw new IOException("Invalid provisioning");
                byte[] data=new byte[(int)inbox.length()];in.readFully(data);
                try{save(context,new JSONObject(new String(data,StandardCharsets.UTF_8)));}finally{Arrays.fill(data,(byte)0);}
            } finally { if(!inbox.delete())throw new IOException("Cannot remove provisioning file"); }
        }
        File file=new File(context.getFilesDir(),"lamp.enc");if(!file.exists())return null;
        if(file.length()>4096 || file.length()<28)throw new IOException("Invalid credentials");
        byte[] raw=new byte[(int)file.length()];try(DataInputStream in=new DataInputStream(new FileInputStream(file))){in.readFully(raw);}
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOf(raw,12)));
        byte[] plain=c.doFinal(Arrays.copyOfRange(raw,12,raw.length));
        try{return validate(new JSONObject(new String(plain,StandardCharsets.UTF_8)));}finally{Arrays.fill(plain,(byte)0);}
    }
}
