package com.android.server;

import android.os.RemoteException;
import android.os.ShellCommand;
import android.text.TextUtils;
import android.util.ArrayMap;

import com.android.internal.space.ConfigProperty;
import com.android.internal.space.INineDSpace;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import android.content.Context;

import android.provider.ContactsContract;
import android.provider.CallLog;
import android.provider.Telephony;
import android.telephony.SmsManager;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.content.ContentUris;
import android.database.Cursor;
import java.util.Calendar;
import android.util.Log;
import android.util.S9Helper;

public class Space9dManagerShellCommand extends ShellCommand {

    private INineDSpace mService;
    private final Context mContext;

    public Space9dManagerShellCommand(INineDSpace service, Context context) {
        this.mService = service;
        this.mContext = context;
    }

    public static int stringToBusinessType(String type) {
        int cpType = ConfigProperty.TYPE_INT;
        if (TextUtils.isEmpty(type)) {
            cpType = -1;
        } else {
            switch (type) {
                case "int":
                    cpType = ConfigProperty.TYPE_INT;
                    break;
                case "bool":
                    cpType = ConfigProperty.TYPE_BOOL;
                    break;
                case "comb":
                    cpType = ConfigProperty.TYPE_COMB;
                    break;
                case "multi":
                    cpType = ConfigProperty.TYPE_MULTI;
                    break;
                default:
                    break;
            }
        }
        return cpType;
    }

    public void runBusModule(PrintWriter pw) throws RemoteException {
        String op = getNextArg();
        String key = getNextArg();
        if ("get".equals(op)) {
            pw.println(mService.getProperty(key).toString());
        } else if ("set".equals(op)) {
            String value = String.format("t:%d v:%s e:%b",
                    stringToBusinessType(getNextArg()),
                    getNextArg(),
                    Boolean.parseBoolean(getNextArg()));
            boolean result = mService.updateModule("business", key, value);
            pw.println(result ? "Success" : "Failure");
        } else if ("update".equals(op)) {
            if ("hidden_apps".equals(key)) {
                mService.updateHiddenApp();
            }
        } else {
            handleDefaultCommands(null);
        }
    }

    private void runSysModule(PrintWriter pw) throws RemoteException {
        String op = getNextArg();
        String key = getNextArg();
        if ("get".equals(op)) {
            pw.println(mService.getPropValue(key));
        } else if ("set".equals(op)) {
            boolean result = mService.updateModule("system", key, getNextArg());
            pw.println(result ? "Success" : "Failure");
        } else {
            handleDefaultCommands(null);
        }
    }

    private void runDump(PrintWriter pw) throws RemoteException {
        String module = getNextArg();
        switch (module) {
            case "business":
                pw.println(mService.dumpModule("business"));
                break;
            case "system":
                pw.println(mService.dumpModule("system"));
                break;
            case "mock":
                String mockModule = getNextArg();
                pw.println(mService.dumpMock(mockModule));
                break;
            default:
                pw.println("Unknown module: " + module);
        }
    }

    private void runUpdateConfig(PrintWriter pw) throws RemoteException {
        ArrayMap<String, String> addMap = new ArrayMap<>();
        List<String> delList = new ArrayList<>();

        String nextOpt;
        String item[];
        while ((nextOpt = getNextArg()) != null) {
            if ("-a".equals(nextOpt)) {
                item = getNextArg().split("=");
                if (item.length == 2) {
                    addMap.put(item[0], item[1]);
                }
            } else if ("-r".equals(nextOpt)) {
                delList.add(getNextArg());
            }
        }
        String[] mockModules = mService.updateMock(addMap, delList);
        StringBuffer buffer = new StringBuffer();
        for (String module : mockModules) {
            buffer.append(module).append(" ");
        }
        int length = buffer.length();
        if (length > 0) {
            buffer.deleteCharAt(length - 1);
        } else {
            buffer.append("0 modules");
        }
        pw.println(String.format("Update mock modules: %s", buffer));
    }

    // 新增：清除所有联系人的方法
    private void clearAllContacts(PrintWriter pw) {
        ContentResolver resolver = mContext.getContentResolver();
        Uri uri = ContactsContract.RawContacts.CONTENT_URI;

        // 执行删除操作
        int deletedCount = resolver.delete(uri, null, null); // 删除所有联系人
        if (deletedCount > 0) {
            pw.println("All contacts deleted successfully.");
        } else {
            pw.println("No contacts found to delete.");
        }
    }

    // 新增：清除所有通话记录的方法
    private void clearAllCallLogs(PrintWriter pw) {
        ContentResolver resolver = mContext.getContentResolver();
        Uri uri = CallLog.Calls.CONTENT_URI;

        // 执行删除操作
        int deletedCount = resolver.delete(uri, null, null); // 删除所有通话记录
        if (deletedCount > 0) {
            pw.println("All call logs deleted successfully.");
        } else {
            pw.println("No call logs found to delete.");
        }
    }

    private void runContact(PrintWriter pw) {
        ArrayMap<String, String> addMap = new ArrayMap<>();
        List<String> delList = new ArrayList<>();
        String nextOpt;
        String[] item;

        while ((nextOpt = getNextArg()) != null) {
            if ("add".equals(nextOpt)) {
                // 继续获取后续参数，直到遇到非键值对参数
                while ((nextOpt = getNextArg()) != null) {
                    item = nextOpt.split("=");
                    if (item.length == 2) {
                        addMap.put(item[0], item[1]);
                    } else {
                        break; // 遇到非键值对参数，退出循环
                    }
                }
            } else if ("-r".equals(nextOpt)) {
                delList.add(getNextArg());
            } else if ("--clear".equals(nextOpt)) { // 新增：检测到 clear 命令
                clearAllContacts(pw); // 调用清除联系人方法
                return; // 结束方法
            }
        }

        // // 打印 addMap
        // pw.println("addMap:");
        // for (Map.Entry<String, String> entry : addMap.entrySet()) {
        // pw.println(entry.getKey() + "=" + entry.getValue());
        // }

        // 打印 delList
        // pw.println("delList:");
        // for (String delItem : delList) {
        // pw.println(delItem);
        // }

        String familyName = null; // 姓
        String givenName = null; // 名
        String phoneNumber = null;
        String email = null;

        try {
            familyName = addMap.get("familyName");
            givenName = addMap.get("givenName");
            phoneNumber = addMap.get("phoneNumber");
            email = addMap.get("email");

            // 验证必填项是否为空
            if (familyName == null || familyName.isEmpty()) {
                pw.println("Warning: familyName is required.");
                return;
            }
            if (givenName == null || givenName.isEmpty()) {
                pw.println("Warning: givenName is required.");
                return;
            }
            if (phoneNumber == null || phoneNumber.isEmpty()) {
                pw.println("Warning: phoneNumber is required.");
                return;
            }

            // 验证电话号码是否为有效的整数
            try {
                Long.parseLong(phoneNumber);
            } catch (NumberFormatException e) {
                pw.println("Warning: phoneNumber must be an Long.");
                return;
            }

            // 验证邮箱格式
            if (email != null && !email.isEmpty()) {
                if (!email.matches("^[\\w.%+-]+@[\\w.-]+\\.[a-zA-Z]{2,6}$")) {
                    pw.println("Warning: Invalid email format.");
                    return;
                }
            }

            pw.println("familyName: " + familyName);
            pw.println("givenName: " + givenName);
            pw.println("phoneNumber: " + phoneNumber);
            pw.println("email: " + email);

            ContentResolver resolver = mContext.getContentResolver();

            // 通过电话号码查询 contactId
            Uri phoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
            String[] phoneProjection = new String[] { ContactsContract.CommonDataKinds.Phone.CONTACT_ID };
            String phoneSelection = ContactsContract.CommonDataKinds.Phone.NUMBER + "=?";
            String[] phoneSelectionArgs = new String[] { phoneNumber };
            Cursor phoneCursor = resolver.query(phoneUri, phoneProjection, phoneSelection, phoneSelectionArgs, null);

            if (phoneCursor != null) {
                while (phoneCursor.moveToNext()) {
                    long contactId = phoneCursor
                            .getLong(phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID));

                    // 根据 contactId 查询姓名和电子邮件
                    Uri dataUri = ContactsContract.Data.CONTENT_URI;
                    String[] dataProjection = new String[] {
                            ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                            ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                            ContactsContract.CommonDataKinds.Email.ADDRESS,
                            ContactsContract.Data.MIMETYPE
                    };
                    String dataSelection = ContactsContract.Data.CONTACT_ID + "=?";
                    String[] dataSelectionArgs = new String[] { String.valueOf(contactId) };
                    Cursor dataCursor = resolver.query(dataUri, dataProjection, dataSelection, dataSelectionArgs, null);

                    if (dataCursor != null) {
                        String existingFamilyName = null;
                        String existingGivenName = null;
                        String existingEmail = null;

                        while (dataCursor.moveToNext()) {
                            String mimeType = dataCursor
                                    .getString(dataCursor.getColumnIndex(ContactsContract.Data.MIMETYPE));
                            if (ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE.equals(mimeType)) {
                                existingFamilyName = dataCursor.getString(dataCursor
                                        .getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME));
                                existingGivenName = dataCursor.getString(dataCursor
                                        .getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME));
                            } else if (ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE.equals(mimeType)) {
                                existingEmail = dataCursor.getString(
                                        dataCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS));
                            }
                        }
                        dataCursor.close();

                        // 检查是否为重复联系人
                        if (familyName.equals(existingFamilyName) && givenName.equals(existingGivenName)
                                && email.equals(existingEmail)) {
                            pw.println(
                                    "Error: Contact with the same familyName, givenName, phoneNumber, and email already exists.");
                            phoneCursor.close();
                            return;
                        }
                    }
                }
                phoneCursor.close();
            }

            // 如果没有重复联系人，继续添加联系人
            // 你的添加联系人代码...

            Uri insertedUri;
            ContentValues values = new ContentValues();

            // 插入RawContact
            Uri rawContactUri = resolver.insert(ContactsContract.RawContacts.CONTENT_URI, values);
            long rawContactId = ContentUris.parseId(rawContactUri);

            // 插入姓和名
            values.clear();
            values.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId);
            values.put(ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE);
            values.put(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, familyName); // 添加姓
            values.put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, givenName); // 添加名
            insertedUri = resolver.insert(ContactsContract.Data.CONTENT_URI, values);
            if (insertedUri == null) {
                pw.println("Error inserting familyName and givenName.");
            }

            // 插入电话号码
            values.clear();
            values.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId);
            values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE);
            values.put(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber);
            values.put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE);
            insertedUri = resolver.insert(ContactsContract.Data.CONTENT_URI, values);
            if (insertedUri == null) {
                pw.println("Error inserting phoneNumber.");
            }

            // 插入Email
            values.clear();
            values.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId);
            values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE);
            values.put(ContactsContract.CommonDataKinds.Email.DATA, email);
            values.put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK);
            insertedUri = resolver.insert(ContactsContract.Data.CONTENT_URI, values);
            if (insertedUri == null) {
                pw.println("Error inserting email.");
            }

            pw.println("Contact inserted successfully: " + insertedUri.toString());
        } catch (NumberFormatException e) {
            pw.println("Failed to parse number: " + e.getMessage());
        } catch (NullPointerException e) {
            pw.println("Failed to get value: " + e.getMessage());
        }
    }

    private void runCallLog(PrintWriter pw) {
        ArrayMap<String, String> addMap = new ArrayMap<>();
        List<String> delList = new ArrayList<>();
        String nextOpt;
        String[] item;

        while ((nextOpt = getNextArg()) != null) {
            if ("add".equals(nextOpt)) {
                // 继续获取后续参数，直到遇到非键值对参数
                while ((nextOpt = getNextArg()) != null) {
                    item = nextOpt.split("=");
                    if (item.length == 2) {
                        addMap.put(item[0], item[1]);
                        // pw.println("=> " + item[0] + ": " + item[1]);
                    } else {
                        break; // 遇到非键值对参数，退出循环
                    }
                }
            } else if ("-r".equals(nextOpt)) {
                delList.add(getNextArg());
            } else if ("--clear".equals(nextOpt)) { // 新增：检测到 clear 命令
                clearAllCallLogs(pw); // 调用清除通话记录方法
                return; // 结束方法
            }
        }

        // // 打印 addMap
        // pw.println("addMap:");
        // for (Map.Entry<String, String> entry : addMap.entrySet()) {
        // pw.println(entry.getKey() + "=" + entry.getValue());
        // }

        String phoneNumber = null;
        int callType = 0;
        long callDuration = 0;
        long date = 0;

        try {
            phoneNumber = addMap.get("phoneNumber");
            if (phoneNumber == null || phoneNumber.isEmpty()) {
                pw.println("Warning: phoneNumber is required.");
                return;
            }

            // 验证电话号码是否为有效的整数
            try {
                Long.parseLong(phoneNumber);
            } catch (NumberFormatException e) {
                pw.println("Warning: phoneNumber must be an Long.");
                return;
            }

            callType = Integer.parseInt(addMap.get("callType"));
            if (callType < 1 || callType > 3) {
                pw.println("Warning: callType must be 1, 2, or 3.");
                return;
            }

            if (addMap.get("callDuration") != null) {
                callDuration = Long.parseLong(addMap.get("callDuration"));
                if (callDuration < 0) {
                    pw.println("Warning: callDuration cannot be negative.");
                    return;
                }
            }

            if (addMap.get("date") == null || addMap.get("date").isEmpty()) {
                pw.println("Warning: date is required.");
                return;
            } else {
                date = Long.parseLong(addMap.get("date"));
            }

            pw.println("phoneNumber: " + phoneNumber);
            pw.println("callType: " + callType);
            pw.println("callDuration: " + callDuration);
            pw.println("date: " + date);

        } catch (NumberFormatException e) {
            pw.println("Failed to parse number: " + e.getMessage());
            return;
        } catch (NullPointerException e) {
            pw.println("Failed to get value: " + e.getMessage());
            return;
        }

        ContentResolver resolver = mContext.getContentResolver();

        // 检查是否存在相同的通话记录
        String selection = CallLog.Calls.NUMBER + "=? AND " +
                CallLog.Calls.TYPE + "=? AND " +
                CallLog.Calls.DURATION + "=? AND " +
                CallLog.Calls.DATE + "=?";
        String[] selectionArgs = { phoneNumber, String.valueOf(callType), String.valueOf(callDuration),
                String.valueOf(date) };

        Cursor cursor = resolver.query(CallLog.Calls.CONTENT_URI, null, selection, selectionArgs, null);
        if (cursor != null && cursor.getCount() > 0) {
            cursor.close();
            pw.println("Error: Duplicate call log entry found. Insertion aborted.");
            return;
        }
        if (cursor != null) {
            cursor.close();
        }

        ContentValues values = new ContentValues();

        // 电话号码
        values.put(CallLog.Calls.NUMBER, phoneNumber);

        // 通话类型（呼入/呼出/未接）
        values.put(CallLog.Calls.TYPE, callType);

        // 通话时长
        values.put(CallLog.Calls.DURATION, callDuration);

        // 通话日期和时间（以毫秒为单位）
        values.put(CallLog.Calls.DATE, date);

        // 是否为新记录
        values.put(CallLog.Calls.NEW, 1);

        // 插入记录
        Uri insertedUri = resolver.insert(CallLog.Calls.CONTENT_URI, values);
        if (insertedUri != null) {
            pw.println("Inserted successfully: " + insertedUri.toString());
        } else {
            pw.println("Error inserting: 未知");
        }
    }

    private void runSms(PrintWriter pw) {
        ArrayMap<String, String> addMap = new ArrayMap<>();
        List<String> delList = new ArrayList<>();
        String nextOpt;
        String[] item;

        while ((nextOpt = getNextArg()) != null) {
            if ("add".equals(nextOpt)) {
                // 继续获取后续参数，直到遇到非键值对参数
                while ((nextOpt = getNextArg()) != null) {
                    item = nextOpt.split("=");
                    if (item.length == 2) {
                        addMap.put(item[0], item[1]);
                        // pw.println("=> " + item[0] + ": " + item[1]);
                    } else {
                        break; // 遇到非键值对参数，退出循环
                    }
                }
            } else if ("-r".equals(nextOpt)) {
                delList.add(getNextArg());
            }
        }

        // 打印 addMap
        pw.println("addMap:");
        for (Map.Entry<String, String> entry : addMap.entrySet()) {
            pw.println(entry.getKey() + "=" + entry.getValue());
        }

        // // 打印 delList
        // pw.println("delList:");
        // for (String delItem : delList) {
        // pw.println(delItem);
        // }

        String address = null;
        String body = null;
        long date = 0;
        int type = 0;

        try {
            address = addMap.get("address");
            if (address == null || address.isEmpty()) {
                pw.println("Warning: address is required.");
                return;
            }

            // 验证电话号码是否为有效的整数
            try {
                Long.parseLong(address);
            } catch (NumberFormatException e) {
                pw.println("Warning: address must be an integer.");
                return;
            }

            body = addMap.get("body");
            if (body == null || body.isEmpty()) {
                pw.println("Warning: body is required.");
                return;
            }

            if (addMap.get("date") == null || addMap.get("date").isEmpty()) {
                pw.println("Warning: date is required.");
                return;
            } else {
                date = Long.parseLong(addMap.get("date"));
            }

            type = Integer.parseInt(addMap.get("type"));
            if (type < 1 || type > 3) {
                pw.println("Warning: type must be 1, 2, or 3.");
                return;
            }

            pw.println("address: " + address);
            pw.println("body: " + body);
            pw.println("date: " + date);
            pw.println("type: " + type);

        } catch (NumberFormatException e) {
            pw.println("Failed to parse number: " + e.getMessage());
            return;
        } catch (NullPointerException e) {
            pw.println("Failed to get value: " + e.getMessage());
            return;
        }

        try {
            ContentResolver resolver = mContext.getContentResolver();
            ContentValues values = new ContentValues();

            // 短信地址（电话号码）
            values.put(Telephony.Sms.ADDRESS, address);

            // 短信内容
            values.put(Telephony.Sms.BODY, body);

            // 短信日期和时间（以毫秒为单位）
            values.put(Telephony.Sms.DATE, date);

            // 短信类型（1: 收件箱, 2: 发件箱）
            values.put(Telephony.Sms.TYPE, type);

            // 插入记录
            Uri uri = (type == Telephony.Sms.MESSAGE_TYPE_INBOX)
                    ? Telephony.Sms.Inbox.CONTENT_URI
                    : Telephony.Sms.Sent.CONTENT_URI;
            Uri insertedUri = resolver.insert(uri, values);

            if (insertedUri != null) {
                pw.println("SMS inserted successfully: " + insertedUri.toString());

                // 查询数据库，确保记录存在
                Cursor cursor = resolver.query(insertedUri, null, null, null, null);

                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String addressInDb = cursor.getString(cursor.getColumnIndex(Telephony.Sms.ADDRESS));
                        String bodyInDb = cursor.getString(cursor.getColumnIndex(Telephony.Sms.BODY));
                        long dateInDb = cursor.getLong(cursor.getColumnIndex(Telephony.Sms.DATE));
                        int typeInDb = cursor.getInt(cursor.getColumnIndex(Telephony.Sms.TYPE));
                        pw.println("SMS: " + addressInDb + ", " + bodyInDb + ", " + dateInDb + ", " + typeInDb);
                    }
                    cursor.close();
                } else {
                    pw.println("Failed to query inserted SMS.");
                }
            } else {
                pw.println("Failed to insert SMS.");
            }
        } catch (Exception e) {
            pw.println("Error inserting SMS: " + e.getMessage());
        }
    }

private void runCtlMap(PrintWriter pw) throws RemoteException {
    String nextOpt = getNextArg();
    
    if (nextOpt == null) {
        pw.println("Error: Operation not specified.");
        return;
    }

    String filePath = getNextArg();
    String key = getNextArg();
    String value = null;
    String result = "";

    switch (nextOpt) {
        case "set":
            value = getNextArg();
            if (filePath == null || key == null || value == null) {
                pw.println("Error: Missing required arguments for 'set' operation.");
                return;
            }
            result = S9Helper.set(filePath, key, value);
            break;

        case "get":
            if (filePath == null || key == null) {
                pw.println("Error: Missing required arguments for 'get' operation.");
                return;
            }
            String defaultValue = "";
            result = S9Helper.get(filePath, key, defaultValue);
            break;

        case "print":
            result = S9Helper.printAll();
            break; 

        default:
            pw.println("Error: Unsupported operation '" + nextOpt + "'.");
            return;
    }

    if (result == null || result.isEmpty()) {
        pw.println("Error: Failed to " + nextOpt + " value, and "+result.isEmpty()+(result == null));
    } else {
        pw.println(nextOpt + " operation successful. Key: " + key + " = " + value + " in file: '" + filePath + "'.");
        pw.println("Result: " + result);
    }
}


    private void insertSmsWithDate(String address, String body, long date, int type) {
        ContentResolver resolver = mContext.getContentResolver();
        ContentValues values = new ContentValues();

        // 短信地址（电话号码）
        values.put(Telephony.Sms.ADDRESS, address);

        // 短信内容
        values.put(Telephony.Sms.BODY, body);

        // 短信日期和时间（以毫秒为单位）
        values.put(Telephony.Sms.DATE, date);

        // 短信类型（1: 收件箱, 2: 发件箱）
        values.put(Telephony.Sms.TYPE, type);

        // 插入记录
        Uri uri = (type == Telephony.Sms.MESSAGE_TYPE_INBOX)
                ? Telephony.Sms.Inbox.CONTENT_URI
                : Telephony.Sms.Sent.CONTENT_URI;
        Uri insertedUri = resolver.insert(uri, values);

        if (insertedUri != null) {
            Log.d("SMS Insert", "SMS inserted successfully: " + insertedUri.toString());
        } else {
            Log.d("SMS Insert", "Failed to insert SMS.");
        }
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        if (mService == null) {
            getErrPrintWriter().println("Space service not found!");
            return 1;
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "business":
                    runBusModule(pw);
                    break;
                case "system":
                    runSysModule(pw);
                    break;
                case "dump":
                    runDump(pw);
                    break;
                case "config":
                    runUpdateConfig(pw);
                    break;
                case "contact":
                    runContact(pw);
                    break;
                case "calllog":
                    runCallLog(pw);
                    break;
                case "sms":
                    runSms(pw);
                    break;
                case "ctlmap":
                    runCtlMap(pw);
                    break;
                default:
                    pw.println("Unknown command: " + cmd);
                    break;
            }
        } catch (RemoteException e) {
            getErrPrintWriter().println(e.getMessage());
            return 1;
        }
        return 0;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Usage: s9 [command] [<arg>...]");
        pw.println("");
        pw.println("The command ares");
        pw.println("      business [get|set|update] [key] <type> <value> <enable>");
        pw.println("            type list: int bool comb multi");
        pw.println("            eg: s9 business set report_url comb http://example.com true");
        pw.println("                s9 business update hidden_apps");
        pw.println("      system [get|set] [key] <value>");
        pw.println("            eg: s9 system set ro.product.name space");
        pw.println("      dump [business|system|mock] <submodule>");
        pw.println("            mock list: [battery|bluetooth|connectivity|vpn|wifi|sim]");
        pw.println("            eg: s9 dump mock wifi");
        pw.println("      config:");
        pw.println("            -a wifi.name=TENDA-9801");
        pw.println("            -r wifi.name");
    }
}
