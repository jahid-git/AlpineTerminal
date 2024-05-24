package com.termux.app;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.*;
import android.webkit.JavascriptInterface;

public class FileUtils {
	
	public static Context context;

	public static void createNewFile(String path) {
		int lastSep = path.lastIndexOf(File.separator);
		if (lastSep > 0) {
			String dirPath = path.substring(0, lastSep);
			makeDir(dirPath);
		}
		File file = new File(path);
		try {
			if (!file.exists())
				file.createNewFile();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static boolean copyAssetFolder(String srcName, String dstName) {
		try {
			boolean result = true;
			String fileList[] = context.getAssets().list(srcName);
			if (fileList == null) return false;

			if (fileList.length == 0) {
				result = copyAssetFile(srcName, dstName);
			} else {
				File file = new File(dstName);
				result = file.mkdirs();
				for (String filename : fileList) {
					result &= copyAssetFolder(srcName + File.separator + filename, dstName + File.separator + filename);
				}
			}
			return result;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	@JavascriptInterface
	public static boolean copyAssetFile(String srcName, String dstName) {
		try {
			createNewFile(dstName);
			InputStream in = context.getAssets().open(srcName);
			File outFile = new File(dstName);
			OutputStream out = new FileOutputStream(outFile);
			byte[] buffer = new byte[1024];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
			in.close();
			out.close();
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}

	public static void deleteFile(String path) {
		File file = new File(path);
		if (!file.exists()) return;
		if (file.isFile()) {
			file.delete();
			return;
		}
		File[] fileArr = file.listFiles();
		if (fileArr != null) {
			for (File subFile : fileArr) {
				if (subFile.isDirectory()) {
					deleteFile(subFile.getAbsolutePath());
				}
				if (subFile.isFile()) {
					subFile.delete();
				}
			}
		}
		file.delete();
	}
	
	@JavascriptInterface
	public static boolean isExistFile(String path) {
		File file = new File(path);
		return file.exists();
	}

	public static void makeDir(String path) {
		if (!isExistFile(path)) {
			File file = new File(path);
			file.mkdirs();
		}
	}

	public static boolean isDirectory(String path) {
		if (!isExistFile(path)) return false;
		return new File(path).isDirectory();
	}

	public static boolean isFile(String path) {
		if (!isExistFile(path)) return false;
		return new File(path).isFile();
	}
}
