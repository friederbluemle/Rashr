package de.mkrtchyan.recoverytools;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;

import org.sufficientlysecure.rootcommands.Shell;
import org.sufficientlysecure.rootcommands.Toolbox;
import org.sufficientlysecure.rootcommands.util.FailedExecuteCommand;

import java.io.File;
import java.io.IOException;

import de.mkrtchyan.utils.Common;

/**
 * Copyright (c) 2015 Aschot Mkrtchyan
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NON INFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
public class FlashUtil extends AsyncTask<Void, Void, Boolean> {

    public static final int JOB_FLASH_RECOVERY = 1;
    public static final int JOB_BACKUP_RECOVERY = 2;
    public static final int JOB_RESTORE_RECOVERY = 3;
    public static final int JOB_FLASH_KERNEL = 4;
    public static final int JOB_BACKUP_KERNEL = 5;
    public static final int JOB_RESTORE_KERNEL = 6;
    private final RashrActivity mActivity;
    private final Context mContext;
    private final Device mDevice;
    final private Shell mShell;
    final private Toolbox mToolbox;
    private final int mJOB;
    private final File mCustomIMG, flash_image, dump_image;
    private ProgressDialog pDialog;
    private File tmpFile, mPartition;
    private boolean keepAppOpen = true;
    private Runnable RunAtEnd;

    private Exception mException = null;

    public FlashUtil(RashrActivity activity, File CustomIMG, int job) {
        mActivity = activity;
        mShell = activity.getShell();
        mContext = activity;
        mDevice = activity.getDevice();
        mJOB = job;
        mCustomIMG = CustomIMG;
        mToolbox = activity.getToolbox();
        flash_image = mDevice.getFlash_image();
        dump_image = mDevice.getDump_image();
        tmpFile = new File(mContext.getFilesDir(), CustomIMG.getName());

        if (isJobRecovery()) {
            mPartition = new File(mDevice.getRecoveryPath());
        } else if (isJobKernel()) {
            mPartition = new File(mDevice.getKernelPath());
        }
    }

    protected void onPreExecute() {
        pDialog = new ProgressDialog(mContext);

        try {
            setBinaryPermissions();
            if (isJobFlash()) {
                pDialog.setTitle(R.string.flashing);
            } else if (isJobBackup()) {
                pDialog.setTitle(R.string.creating_bak);
            } else if (isJobRestore()) {
                pDialog.setTitle(R.string.restoring);
            }
            if (isJobBackup() && (isJobRecovery() ? mDevice.isRecoveryDD() : mDevice.isKernelDD())) {
                pDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                pDialog.setMax(getSizeOfFile(mPartition));
            } else {
                pDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
            }
            pDialog.setMessage(mCustomIMG.getName());
            pDialog.setCancelable(false);
            pDialog.show();
        } catch (FailedExecuteCommand e) {
            mActivity.addError(Const.FLASH_UTIL_TAG, e, true);
        }

    }

    @Override
    protected Boolean doInBackground(Void... params) {
        try {
            int PartitionType = 0;
            if (isJobRecovery()) {
                PartitionType = mDevice.getRecoveryType();
            } else if (isJobKernel()) {
                PartitionType = mDevice.getKernelType();
            }
            switch (PartitionType) {
                case Device.PARTITION_TYPE_MTD:
                    MTD();
                    break;
                case Device.PARTITION_TYPE_DD:
                    DD();
                    break;
                //case Device.PARTITION_TYPE_SONY:
                //    SONY();
                //    break;
                default:
                    return false;
            }
            saveHistory();
            return true;
        } catch (Exception e) {
            mException = e;
            return false;
        }
    }

    protected void onPostExecute(Boolean success) {
        pDialog.dismiss();
        if (!success) {
            if (mException != null) {
                mActivity.addError(Const.FLASH_UTIL_TAG, mException, true);
            }
        } else if (tmpFile.delete()) {
            if (RunAtEnd != null) RunAtEnd.run();
            if (isJobFlash() || isJobRestore()) {
                if (!Common.getBooleanPref(mContext, Const.PREF_NAME, Const.PREF_KEY_HIDE_REBOOT)) {
                    showRebootDialog();
                } else {
                    if (!keepAppOpen) {
                        System.exit(0);
                    }
                }
            }
        }
    }

    public void DD() throws FailedExecuteCommand, IOException {
        Thread observer;

        if (isJobBackup() && (isJobRecovery() ? mDevice.isRecoveryDD() : mDevice.isKernelDD())) {
            observer = new Thread(new Runnable() {
            @Override
            public void run() {
                    while (true) {
                        try {
                            final int progress = Common.safeLongToInt(tmpFile.length());
                            mActivity.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    pDialog.setProgress(progress);
                                }
                            });
                            if (progress >= pDialog.getMax()) break;
                        } catch (IllegalArgumentException e) {
                            mActivity.addError(Const.FLASH_UTIL_TAG, e, false);
                            pDialog.setProgress(pDialog.getMax());
                            break;
                        }
                    }
                }
            });
            observer.start();
        }
        if (isJobFlash()) {
            int customSize = getSizeOfFile(mCustomIMG);
            int partitionSize = getSizeOfFile(mPartition);
            /** ERROR on some chinese devices. Partition size always 0 */
            if (partitionSize != 0) {
                if (customSize > partitionSize) {
                    throw new IOException("IMG is to big for your device! IMG Size: " +
                            customSize / (1024 * 1024) + "MB Partition Size: " +
                            partitionSize / (1024 * 1024) + "MB");
                }
            }
        }

        String Command = "";
        if (isJobFlash() || isJobRestore()) {
            if (mDevice.isLoki() && isJobFlash()) {
                Command = lokiPatch();
            } else {
                Common.copyFile(mCustomIMG, tmpFile);
                Command = Const.Busybox + " dd if=\"" + tmpFile + "\" of=\"" + mPartition + "\"";
                if ((isJobRecovery() ? mDevice.getRecoveryBlocksize() : mDevice.getKernelBlocksize()) > 0) {
                    String bs = "bs="
                            + (isJobRecovery() ? mDevice.getRecoveryBlocksize() : mDevice.getKernelBlocksize());
                    Command += bs;
                }
            }
        } else if (isJobBackup()) {
            Command = Const.Busybox + " dd if=\"" + mPartition + "\" of=\"" + tmpFile + "\"";
        }
        mShell.execCommand(Command, true);
        if (isJobBackup()) placeImgBack();
    }

    public void MTD() throws FailedExecuteCommand, IOException {
        String Command;
        if (isJobRecovery()) {
            Command = " recovery ";
        } else if (isJobKernel()) {
            Command = " boot ";
        } else {
            return;
        }
        if (isJobFlash() || isJobRestore()) {
            Command = flash_image.getAbsolutePath() + Command + "\"" + tmpFile.getAbsolutePath() + "\"";
        } else if (isJobBackup()) {
            Command = dump_image.getAbsolutePath() + Command + "\"" + tmpFile.getAbsolutePath() + "\"";
        }
        mShell.execCommand(Command, true);
        if (isJobBackup()) placeImgBack();
    }

    /*public void SONY() throws FailedExecuteCommand, IOException {

        String Command = "";
        if (mDevice.getName().equals("yuga")
                || mDevice.getName().equals("c6602")
                || mDevice.getName().equals("montblanc")) {
            if (isJobFlash() || isJobRestore()) {
                File charger = new File(Const.PathToUtils, "charger");
                File chargermon = new File(Const.PathToUtils, "chargermon");
                File ric = new File(Const.PathToUtils, "ric");
                mToolbox.remount(mPartition, "RW");
                try {
                    mToolbox.copyFile(charger, mPartition.getParentFile(), true, false);
                    mToolbox.copyFile(chargermon, mPartition.getParentFile(), true, false);
                    if (mDevice.getName().equals("yuga")
                            || mDevice.getName().equals("c6602")) {
                        mToolbox.copyFile(ric, mPartition.getParentFile(), true, false);
                        mToolbox.setFilePermissions(ric, "755");
                    }
                } catch (Exception e) {
                    mActivity.addError(Const.FLASH_UTIL_TAG, e, true);
                }
                mToolbox.setFilePermissions(charger, "755");
                mToolbox.setFilePermissions(chargermon, "755");
                mToolbox.setFilePermissions(mCustomIMG, "644");
                mToolbox.remount(mPartition, "RO");
                Command = "cat " + mCustomIMG.getAbsolutePath() + " >> " + mPartition.getAbsolutePath();
            } else if (isJobBackup()) {
                Command = "cat " + mPartition.getAbsolutePath() + " >> " + mCustomIMG.getAbsolutePath();
            }
        }
        mShell.execCommand(Command, true);
        if (isJobBackup()) placeImgBack();
    }*/

    private void setBinaryPermissions() throws FailedExecuteCommand {
        try {
            mToolbox.setFilePermissions(Const.Busybox, "755");
        } catch (FailedExecuteCommand e) {
            mToolbox.remount(Const.Busybox, "rw");
            mToolbox.setFilePermissions(Const.Busybox, "755");
            mToolbox.remount(Const.Busybox, "ro");
        }
		try {
			mToolbox.setFilePermissions(flash_image, "755");
		} catch (FailedExecuteCommand e) {
			mToolbox.remount(flash_image, "rw");
			mToolbox.setFilePermissions(flash_image, "755");
			mToolbox.remount(flash_image, "ro");
		}
		try {
			mToolbox.setFilePermissions(dump_image, "755");
		} catch (FailedExecuteCommand e) {
			mToolbox.remount(dump_image, "rw");
			mToolbox.setFilePermissions(dump_image, "755");
			mToolbox.remount(dump_image, "ro");
		}
    }

    public void showRebootDialog() {
	    int Message;
	    final int REBOOT_JOB;
	    if (isJobKernel()) {
		    Message = R.string.reboot_now;
		    REBOOT_JOB = Toolbox.REBOOT_REBOOT;
	    } else {
		    Message = R.string.reboot_recovery_now;
		    REBOOT_JOB = Toolbox.REBOOT_RECOVERY;
	    }

        new AlertDialog.Builder(mContext)
                .setTitle(R.string.flashed)
                .setMessage(Message)
                .setPositiveButton(R.string.positive, new DialogInterface.OnClickListener() {
	                @Override
	                public void onClick(DialogInterface dialogInterface, int i) {

		                try {
			                mToolbox.reboot(REBOOT_JOB);
		                } catch (Exception e) {
                            mActivity.addError(Const.FLASH_UTIL_TAG, e, false);
		                }
	                }
                })
                .setNeutralButton(R.string.neutral, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        if (!keepAppOpen) {
                            System.exit(0);
                        }
                    }
                })
                .setNegativeButton(R.string.never_again, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Common.setBooleanPref(mContext, Const.PREF_NAME, Const.PREF_KEY_HIDE_REBOOT,
                                true);
                        if (!keepAppOpen) {
                            System.exit(0);
                        }
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialogInterface) {
                        if (!keepAppOpen) {
                            System.exit(0);
                        }
                    }
                })
                .setCancelable(keepAppOpen)
                .show();
    }

    private void placeImgBack() throws IOException, FailedExecuteCommand {
        mToolbox.setFilePermissions(tmpFile, "666");
        Common.copyFile(tmpFile, mCustomIMG);
    }

    public void saveHistory() {
        if (isJobFlash()) {
            switch (Common.getIntegerPref(mContext, Const.PREF_NAME, Const.PREF_KEY_FLASH_COUNTER)) {
                case 0:
                    Common.setStringPref(mContext, Const.PREF_NAME, Const.PREF_KEY_HISTORY +
                                    String.valueOf(Common.getIntegerPref(mContext, Const.PREF_NAME,
                                            Const.PREF_KEY_FLASH_COUNTER)),
                            mCustomIMG.getAbsolutePath()
                    );
                    Common.setIntegerPref(mContext, Const.PREF_NAME, Const.PREF_KEY_FLASH_COUNTER, 1);
                    return;
                default:
                    Common.setStringPref(mContext, Const.PREF_NAME, Const.PREF_KEY_HISTORY +
                                    String.valueOf(Common.getIntegerPref(mContext, Const.PREF_NAME,
                                            Const.PREF_KEY_FLASH_COUNTER)),
                            mCustomIMG.getAbsolutePath()
                    );
                    Common.setIntegerPref(mContext, Const.PREF_NAME, Const.PREF_KEY_FLASH_COUNTER,
                            Common.getIntegerPref(mContext, Const.PREF_NAME, Const.PREF_KEY_FLASH_COUNTER) + 1);
                    if (Common.getIntegerPref(mContext, Const.PREF_NAME, Const.PREF_KEY_FLASH_COUNTER) == 5) {
                        Common.setIntegerPref(mContext, Const.PREF_NAME, Const.PREF_KEY_FLASH_COUNTER, 0);
                    }
            }
        }
    }

    public void setKeepAppOpen(boolean keepAppOpen) {
        this.keepAppOpen = keepAppOpen;
    }

    public boolean isJobFlash() {
        return mJOB == JOB_FLASH_RECOVERY || mJOB == JOB_FLASH_KERNEL;
    }

    public boolean isJobRestore() {
        return mJOB == JOB_RESTORE_KERNEL || mJOB == JOB_RESTORE_RECOVERY;
    }

    public boolean isJobBackup() {
        return mJOB == JOB_BACKUP_RECOVERY || mJOB == JOB_BACKUP_KERNEL;
    }

    public boolean isJobKernel() {
        return mJOB == JOB_BACKUP_KERNEL || mJOB == JOB_RESTORE_KERNEL || mJOB == JOB_FLASH_KERNEL;
    }

    public boolean isJobRecovery() {
        return mJOB == JOB_BACKUP_RECOVERY || mJOB == JOB_RESTORE_RECOVERY || mJOB == JOB_FLASH_RECOVERY;
    }

    public void setRunAtEnd(Runnable RunAtEnd) {
        this.RunAtEnd = RunAtEnd;
    }

    public String lokiPatch() throws FailedExecuteCommand {
        File aboot = new File("/dev/block/platform/msm_sdcc.1/by-name/aboot");
        File extracted_aboot = new File(mContext.getFilesDir(), "aboot.img");
        File patched_CustomIMG = new File(mContext.getFilesDir(), mCustomIMG.getName() + ".lok");
        File loki_patch = new File(mContext.getFilesDir(), "loki_patch");
        File loki_flash = new File(mContext.getFilesDir(), "loki_flash");
        mShell.execCommand("dd if=" + aboot + " of=" + extracted_aboot, true);
        mShell.execCommand(loki_patch + " recovery " + mCustomIMG + " " + patched_CustomIMG +
                "  || exit 1", true);
        return loki_flash + " recovery " + patched_CustomIMG + " || exit 1";
    }

    public int getSizeOfFile(File path) {
        try {
            String output;
            output = mShell.execCommand("wc -c " + path);
            return Integer.valueOf(output.split(" ")[0]);
        } catch (FailedExecuteCommand failedExecuteCommand) {
            return -1;
        }
    }
}