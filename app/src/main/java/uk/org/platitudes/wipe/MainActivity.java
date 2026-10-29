package uk.org.platitudes.wipe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.File;
import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int PICK_FILES = 42;
    private static final int MAX_NAME_CHANGES = 100, MAX_PASSES = 100;
    private final List<Uri> selectedFiles = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private volatile boolean cancelRequested = false;
    private volatile boolean wipeRunning = false;
    private TextView selectedCount, selectedFilesView, statusView, stageView;
    private EditText editNameChanges, editContentPasses;
    private Button pickButton, wipeButton, cancelButton;
    private ProgressBar progressBar;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(Color.rgb(8,13,12));
        getWindow().setNavigationBarColor(Color.rgb(8,13,12));
        selectedCount=findViewById(R.id.selectedCount); selectedFilesView=findViewById(R.id.selectedFiles);
        statusView=findViewById(R.id.status); stageView=findViewById(R.id.stage);
        progressBar=findViewById(R.id.progressBar); editNameChanges=findViewById(R.id.editNameChanges);
        editContentPasses=findViewById(R.id.editContentPasses); pickButton=findViewById(R.id.btnPick);
        wipeButton=findViewById(R.id.btnWipe); cancelButton=findViewById(R.id.btnCancel);
        pickButton.setOnClickListener(v -> pickFiles()); wipeButton.setOnClickListener(v -> confirmWipe());
        cancelButton.setOnClickListener(v -> requestCancel()); setRunningUi(false); refreshSelection();
    }

    private void pickFiles() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i,PICK_FILES); }
        catch (Exception e) { statusView.setText("File picker could not be opened: "+safeMessage(e)); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK_FILES || resultCode!=RESULT_OK || data==null) return;
        selectedFiles.clear(); int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if(data.getClipData()!=null) {
            for(int i=0;i<data.getClipData().getItemCount();i++) addPickedUri(data.getClipData().getItemAt(i).getUri(),flags);
        } else if(data.getData()!=null) addPickedUri(data.getData(),flags);
        refreshSelection();
    }
    private void addPickedUri(Uri uri,int flags) {
        try { getContentResolver().takePersistableUriPermission(uri,flags); } catch(Exception ignored) { }
        if(!selectedFiles.contains(uri)) selectedFiles.add(uri);
    }

    private void refreshSelection() {
        selectedCount.setText(String.format(Locale.getDefault(),"%02d FILE(S) SELECTED",selectedFiles.size()));
        if(selectedFiles.isEmpty()) selectedFilesView.setText("No files selected.");
        else { StringBuilder b=new StringBuilder(); for(int i=0;i<selectedFiles.size();i++) {
            Uri u=selectedFiles.get(i); b.append(i+1).append(". ").append(displayName(u)); long n=fileSize(u);
            if(n>=0)b.append("  ·  ").append(formatSize(n)); if(i+1<selectedFiles.size())b.append('\n'); }
            selectedFilesView.setText(b.toString()); }
        if(!wipeRunning) { stageView.setText("READY"); statusView.setText("Select files, review settings, then confirm."); }
        wipeButton.setEnabled(!selectedFiles.isEmpty()&&!wipeRunning); wipeButton.setAlpha(wipeButton.isEnabled()?1f:.55f);
    }
    private void confirmWipe() {
        if(selectedFiles.isEmpty()) { Toast.makeText(this,"Select files first",Toast.LENGTH_SHORT).show(); return; }
        int renames=readSetting(editNameChanges,10,1,MAX_NAME_CHANGES), passes=readSetting(editContentPasses,3,1,MAX_PASSES);
        new AlertDialog.Builder(this).setTitle("Confirm deletion")
            .setMessage("Files: "+selectedFiles.size()+"\nFilename changes: "+renames+"\nOverwrite passes: "+passes+
                "\n\nThis permanently deletes selected files if the storage provider allows it. Continue only if these are the correct files.")
            .setNegativeButton("Go back",null).setPositiveButton("Wipe selected",(d,w)->startWipe(renames,passes)).show();
    }
    private int readSetting(EditText e,int fallback,int min,int max) { try { return Math.max(min,Math.min(Integer.parseInt(e.getText().toString().trim()),max)); } catch(Exception ex){return fallback;} }
    private void setRunningUi(boolean running) {
        wipeRunning=running; pickButton.setEnabled(!running); wipeButton.setEnabled(!running&&!selectedFiles.isEmpty());
        cancelButton.setEnabled(running); cancelButton.setAlpha(running?1f:.55f);
    }
    private void requestCancel() { if(wipeRunning) { cancelRequested=true; cancelButton.setEnabled(false); stageView.setText("CANCELLATION REQUESTED"); statusView.setText("Stopping at the next safe checkpoint. Changes already made cannot be undone."); } }

    private void startWipe(int renameAttempts,int passes) {
        final List<Uri> work=new ArrayList<>(selectedFiles); cancelRequested=false; setRunningUi(true);
        progressBar.setProgress(0); stageView.setText("PREPARING");
        new Thread(()->{
            StringBuilder log=new StringBuilder(); int deleted=0,failed=0,cancelled=0;
            for(int idx=0;idx<work.size();idx++) {
                if(cancelRequested) break;
                final int fileNo=idx+1; Uri original=work.get(idx); String name=displayName(original);
                try {
                    update(fileNo,work.size(),0,"FILE "+fileNo+" OF "+work.size(),"Checking file access: "+name);
                    // Confirm that the provider grants write access before any destructive operation.
                    try(ParcelFileDescriptor ignored=getContentResolver().openFileDescriptor(original,"rw")) {
                        if(ignored==null) throw new IllegalStateException("Write access was not granted. Select the file again and allow access.");
                    }
                    // Real AES-256-GCM encryption of the selected file, followed by replacing its logical contents.
                    encryptAndReplace(original,fileNo,work.size(),name);
                    checkCancel();
                    // Discard the generated key after ciphertext has been written, then overwrite the file.
                    int renamed=0;
                    for(int pass=0;pass<passes;pass++) overwritePass(original,pass,passes,fileNo,work.size(),name);
                    Uri working=original;
                    for(int r=0;r<renameAttempts;r++) {
                        checkCancel(); update(fileNo,work.size(),92,"RENAMING FILE",name+" · attempt "+(r+1)+" of "+renameAttempts);
                        try { Uri next=DocumentsContract.renameDocument(getContentResolver(),working,"wipe-"+randomHex(12)); if(next==null)break; working=next;renamed++; }
                        catch(Exception unsupported){break;}
                    }
                    checkCancel(); update(fileNo,work.size(),97,"DELETING FILE",name);
                    if(!DocumentsContract.deleteDocument(getContentResolver(),working)) throw new IllegalStateException("The storage provider did not confirm deletion.");
                    deleted++; log.append("✓ DELETED  ").append(name).append("\n  Encryption: AES-256-GCM · Filename changes: ").append(renamed).append('/').append(renameAttempts).append(" · Overwrite passes: ").append(passes).append('\n');
                } catch(Cancelled e) { cancelled++; log.append("! CANCELLED  ").append(name).append("\n  May be partly modified; not intentionally deleted after cancellation.\n"); break;
                } catch(Exception e) { failed++; log.append("✕ FAILED  ").append(name).append("\n  ").append(safeMessage(e)).append('\n'); }
                final int overall=(int)(100.0*(idx+1)/work.size()); runOnUiThread(()->progressBar.setProgress(overall));
            }
            final boolean wasCancelled=cancelRequested || cancelled>0;
            final int failedCount=failed;
            log.append("\n").append(wasCancelled?"CANCELLED":"FINISHED").append(" · Deleted: ").append(deleted).append(" · Failed: ").append(failedCount);
            runOnUiThread(()->{ setRunningUi(false); stageView.setText(wasCancelled?"CANCELLED":"FINISHED"); statusView.setText(log.toString());
                if(!wasCancelled && failedCount==0) progressBar.setProgress(100); selectedFiles.clear(); refreshSelection(); });
        }).start();
    }

    private void encryptAndReplace(Uri uri,int fileNo,int fileTotal,String name) throws Exception {
        checkCancel();
        update(fileNo,fileTotal,1,"ENCRYPTING FILE","Encrypting "+name+" with AES-256-GCM");
        byte[] key=new byte[32]; random.nextBytes(key);
        byte[] nonce=new byte[12]; random.nextBytes(nonce);
        File encrypted=File.createTempFile("wipe-encrypted-", ".bin", getCacheDir());
        boolean replaced=false;
        try {
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            try(InputStream in=getContentResolver().openInputStream(uri)) {
                if(in==null) throw new IllegalStateException("Could not read selected file.");
                try(FileOutputStream fileOut=new FileOutputStream(encrypted)) {
                    fileOut.write(nonce);
                    try(CipherOutputStream cipherOut=new CipherOutputStream(fileOut,cipher)) {
                        byte[] buffer=new byte[64*1024]; int read; long done=0; long size=fileSize(uri);
                        while((read=in.read(buffer))!=-1) {
                            checkCancel(); cipherOut.write(buffer,0,read); done+=read;
                            int pct=size>0?(int)Math.min(15,1+(done*14/size)):8;
                            update(fileNo,fileTotal,pct,"ENCRYPTING FILE",name+" · "+(size>0?(done*100/size):0)+"% processed");
                        }
                    }
                    fileOut.getFD().sync();
                }
            }
            checkCancel(); update(fileNo,fileTotal,16,"WRITING ENCRYPTED CONTENT","Replacing file contents with encrypted data");
            try(ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(uri,"rwt")) {
                if(pfd==null) throw new IllegalStateException("This storage provider does not support replacing file contents.");
                try(FileOutputStream out=new FileOutputStream(pfd.getFileDescriptor()); FileInputStream in=new FileInputStream(encrypted)) {
                    byte[] buffer=new byte[64*1024]; int read; long done=0,total=encrypted.length();
                    while((read=in.read(buffer))!=-1) {
                        checkCancel(); out.write(buffer,0,read); done+=read;
                        int pct=total>0?(int)(16+14*done/total):30;
                        update(fileNo,fileTotal,pct,"WRITING ENCRYPTED CONTENT",name);
                    }
                    out.flush(); out.getFD().sync();
                }
            }
            replaced=true;
        } finally {
            java.util.Arrays.fill(key,(byte)0);
            if(encrypted.exists() && !encrypted.delete()) encrypted.deleteOnExit();
            if(!replaced) update(fileNo,fileTotal,0,"ENCRYPTION INCOMPLETE","Original file may be unchanged or partially modified if replacement had started.");
        }
    }

    private void overwritePass(Uri uri,int pass,int total,int fileNo,int fileTotal,String name) throws Exception {
        checkCancel(); long size=fileSize(uri); if(size<0)size=measureSize(uri);
        if(size==0) return;
        byte[] bytes=new byte[64*1024]; long written=0;
        try(ParcelFileDescriptor pfd=getContentResolver().openFileDescriptor(uri,"rw")) {
            if(pfd==null) throw new IllegalStateException("Android did not grant write access. Re-select this file.");
            try(FileOutputStream out=new FileOutputStream(pfd.getFileDescriptor())) {
                FileChannel channel=out.getChannel(); channel.position(0);
                while(written<size) {
                    checkCancel(); random.nextBytes(bytes); int chunk=(int)Math.min(bytes.length,size-written);
                    ByteBuffer buffer=ByteBuffer.wrap(bytes,0,chunk); while(buffer.hasRemaining())channel.write(buffer); written+=chunk;
                    int inPass=(int)(written*100/size); int overall=(int)(((pass+(inPass/100.0))/total)*85);
                    update(fileNo,fileTotal,overall,"OVERWRITING CONTENT",name+" · pass "+(pass+1)+" of "+total+" · "+inPass+"% of pass");
                }
                channel.force(true);
            }
        }
    }
    private void update(int fileNo,int total,int progress,String stage,String detail) { runOnUiThread(()->{stageView.setText(stage);statusView.setText("File "+fileNo+" of "+total+"\n"+detail);progressBar.setProgress(progress);}); }
    private void checkCancel() throws Cancelled { if(cancelRequested)throw new Cancelled(); }
    private static class Cancelled extends Exception { }
    private long measureSize(Uri u)throws Exception {long n=0;byte[] b=new byte[65536];try(InputStream in=getContentResolver().openInputStream(u)){if(in==null)throw new IllegalStateException("Cannot read selected file.");int r;while((r=in.read(b))!=-1)n+=r;}return n;}
    private String displayName(Uri u) {try(Cursor c=getContentResolver().query(u,null,null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(i>=0){String n=c.getString(i);if(n!=null&&!n.isEmpty())return n;}}}catch(Exception ignored){}String s=u.getLastPathSegment();return s==null?"(unnamed file)":s;}
    private long fileSize(Uri u) {try(Cursor c=getContentResolver().query(u,null,null,null,null)){if(c!=null&&c.moveToFirst()){int i=c.getColumnIndex(OpenableColumns.SIZE);if(i>=0&&!c.isNull(i)){long n=c.getLong(i);if(n>=0)return n;}}}catch(Exception ignored){}return -1;}
    private String randomHex(int chars){char[] a="0123456789abcdef".toCharArray();StringBuilder b=new StringBuilder();for(int i=0;i<chars;i++)b.append(a[random.nextInt(a.length)]);return b.toString();}
    private String formatSize(long n){if(n<1024)return n+" B";if(n<1048576)return String.format(Locale.getDefault(),"%.1f KB",n/1024.0);return String.format(Locale.getDefault(),"%.1f MB",n/1048576.0);}
    private String safeMessage(Exception e){String m=e.getMessage();if(m==null||m.trim().isEmpty())return e.getClass().getSimpleName();return m;}
}