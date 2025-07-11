// src/common/com/xu/service/IXu.aidl
package com.xu.service;

import android.os.ParcelFileDescriptor;
import com.xu.service.CommandResult;

interface IXu {
    ParcelFileDescriptor createPtySession(int uid, in String[] commandArgs, in String[] envVars);
    ParcelFileDescriptor createStreamingShellSession(int uid, in String[] envVars);
    CommandResult executeBatchCommand(int uid, in String[] commandArgs, in String[] envVars);
}