/*
 * Copyright (C) 2015 HouKx <hkx.aidream@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.pluginmgr;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import android.content.Context;
import android.content.IntentFilter;
import android.content.IntentFilter.MalformedMimeTypeException;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.PatternMatcher;
import android.text.TextUtils;

/**
 * ZX 使用 PackageManager 读取 packageInfo
 *
 * @author HouKangxi
 * 
 */
class PluginManifestUtil {
	static void setManifestInfo(Context context, String apkPath, PlugInfo info)
			throws XmlPullParserException, IOException {
		
		ZipFile zipFile = new ZipFile(new File(apkPath), ZipFile.OPEN_READ);
		ZipEntry manifestXmlEntry = zipFile.getEntry(XmlManifestReader.DEFAULT_XML);
		
		String manifestXML = XmlManifestReader.getManifestXMLFromAPK(zipFile,
				manifestXmlEntry);
		PackageInfo pkgInfo = context.getPackageManager()
				.getPackageArchiveInfo(
						apkPath,
						PackageManager.GET_ACTIVITIES
								| PackageManager.GET_SERVICES // ZX 加入Services信息
								| PackageManager.GET_RECEIVERS//
								| PackageManager.GET_PROVIDERS//
								| PackageManager.GET_META_DATA//
								| PackageManager.GET_SHARED_LIBRARY_FILES//
				// | PackageManager.GET_SERVICES//
				// | PackageManager.GET_SIGNATURES//
				);
		// Log.d("ManifestReader: setManifestInfo", "GET_SHARED_LIBRARY_FILES="
		// + pkgInfo.applicationInfo.nativeLibraryDir);
		info.setPackageInfo(pkgInfo);
		File libdir = ActivityOverider.getPluginLibDir(info.getId());
		try {
			if(extractLibFile(zipFile, libdir)){
				pkgInfo.applicationInfo.nativeLibraryDir=libdir.getAbsolutePath();
			}
		} finally {
			zipFile.close();
		}
		setAttrs(info, manifestXML);
	}
	private static boolean extractLibFile(ZipFile zip, File tardir)
			throws ZipException, IOException {
		
		
		String defaultArch = "armeabi";
        Map<String,List<ZipEntry>> archLibEntries = new HashMap<String, List<ZipEntry>>();
		for (Enumeration<? extends ZipEntry> e = zip.entries(); e
				.hasMoreElements();) {
			ZipEntry entry = e.nextElement();
			String name = entry.getName();
			if (name.startsWith("/")) {
				name = name.substring(1);
			}
			if (name.startsWith("lib/")) {
				if(entry.isDirectory()){
					continue;
				}
				int sp = name.indexOf('/', 4);
				String en2add;
				if (sp > 0) {
					String osArch = name.substring(4, sp);
					en2add=osArch.toLowerCase();
				} else {
					en2add=defaultArch;
				}
				List<ZipEntry> ents = archLibEntries.get(en2add);
				if (ents == null) {
					ents = new LinkedList<ZipEntry>();
					archLibEntries.put(en2add, ents);
				}
				ents.add(entry);
			}
		}
		String arch = System.getProperty("os.arch");
		List<ZipEntry> libEntries = archLibEntries.get(arch.toLowerCase());
		if (libEntries == null) {
			libEntries = archLibEntries.get(defaultArch);
		}
		boolean hasLib = false;
		if (libEntries != null) {
			hasLib = true;
			if (!tardir.exists()) {
				tardir.mkdirs();
			}
			for (ZipEntry libEntry : libEntries) {
				String ename = libEntry.getName();
				String pureName = ename.substring(ename.lastIndexOf('/') + 1);
				File target = new File(tardir, pureName);
				FileUtil.writeToFile(zip.getInputStream(libEntry), target);
			}
		}
		
		return hasLib;
	}
	private static void setAttrs(PlugInfo info, String manifestXML)
			throws XmlPullParserException, IOException {
		XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
		factory.setNamespaceAware(true);
		XmlPullParser parser = factory.newPullParser();
		parser.setInput(new StringReader(manifestXML));
		int eventType = parser.getEventType();
		String namespaceAndroid = null;
		do {
			switch (eventType) {
			case XmlPullParser.START_DOCUMENT: {
				break;
			}
			case XmlPullParser.START_TAG: {
				String tag = parser.getName();
				if (tag.equals("manifest")) {
					namespaceAndroid = parser.getNamespace("android");
				} else if ("activity".equals(tag)) {
					addActivity(info, namespaceAndroid, parser);
				} else if ("receiver".equals(tag)) {
					addReceiver(info, namespaceAndroid, parser);
				} else if ("service".equals(tag)) {
					addService(info, namespaceAndroid, parser);
				}else if("application".equals(tag)){
					parseApplicationInfo(info, namespaceAndroid, parser);
				}
				break;
			}
			case XmlPullParser.END_TAG: {
				break;
			}
			}
			eventType = parser.next();
		} while (eventType != XmlPullParser.END_DOCUMENT);
	}

	private static void parseApplicationInfo(PlugInfo info,
			String namespace, XmlPullParser parser) throws XmlPullParserException, IOException{
		String applicationName = parser.getAttributeValue(namespace, "name");
		String packageName = info.getPackageInfo().packageName;
		ApplicationInfo applicationInfo = info.getPackageInfo().applicationInfo;
		applicationInfo.name = getName(applicationName, packageName);
	}

	private static void addActivity(PlugInfo info, String namespace,
			XmlPullParser parser) throws XmlPullParserException, IOException {
		String activityName = parser.getAttributeValue(namespace, "name");
		String packageName = info.getPackageInfo().packageName;
		activityName = getName(activityName, packageName);
		ResolveInfo act = new ResolveInfo();
		act.activityInfo = info.findActivityByClassNameFromPkg(activityName);
		//
		parseInner(namespace, parser, act, "activity");
		info.addActivity(act);
	}

	private static void addService(PlugInfo info, String namespace,
			XmlPullParser parser) throws XmlPullParserException, IOException {
		String serviceName = parser.getAttributeValue(namespace, "name");
		String packageName = info.getPackageInfo().packageName;
		serviceName = getName(serviceName, packageName);
		ResolveInfo service = new ResolveInfo();
		service.serviceInfo = info.findServiceByClassName(serviceName);
		//
		parseInner(namespace, parser, service, "service");
		info.addService(service);
	}

	private static void addReceiver(PlugInfo info, String namespace,
			XmlPullParser parser) throws XmlPullParserException, IOException {
		String receiverName = parser.getAttributeValue(namespace, "name");
		String packageName = info.getPackageInfo().packageName;
		receiverName = getName(receiverName, packageName);
		ResolveInfo receiver = new ResolveInfo();
		// 此时的activityInfo 表示 receiverInfo
		receiver.activityInfo = info.findReceiverByClassName(receiverName);
		parseInner(namespace, parser, receiver, "receiver");
		//
		info.addReceiver(receiver);
	}
	
	private static void  parseInner(String namespace,XmlPullParser parser,ResolveInfo rsinfo,String tagName) throws XmlPullParserException, IOException{
		int eventType = parser.getEventType();
		do {
			switch (eventType) {
			case XmlPullParser.START_TAG: {
				String tag = parser.getName();
				if ("intent-filter".equals(tag)) {
					if (rsinfo.filter == null) {
						rsinfo.filter = new IntentFilter();
					}
				} else if ("action".equals(tag)) {
					String actionName = parser.getAttributeValue(namespace,
							"name");
					rsinfo.filter.addAction(actionName);
				} else if ("category".equals(tag)) {
					String category = parser.getAttributeValue(namespace,
							"name");
					rsinfo.filter.addCategory(category);
				} else if ("data".equals(tag)) {
					// TODO parse data
					String scheme = parser.getAttributeValue(namespace, "scheme");
					String host = parser.getAttributeValue(namespace, "host");
					String port = parser.getAttributeValue(namespace, "port");
					int pathType = PatternMatcher.PATTERN_LITERAL;
					String path = parser.getAttributeValue(namespace, "path");
					if(TextUtils.isEmpty(path)){
						pathType = PatternMatcher.PATTERN_PREFIX;
						path = parser.getAttributeValue(namespace, "pathPrefix");
						if(TextUtils.isEmpty(path)){
							pathType = PatternMatcher.PATTERN_SIMPLE_GLOB;
							path = parser.getAttributeValue(namespace, "pathPattern");
						}
					}
					String mimeType = parser.getAttributeValue(namespace, "mimeType");
					if(host!=null){
						rsinfo.filter.addDataAuthority(host, port);
					}else if(path!=null){
						rsinfo.filter.addDataPath(path, pathType);
					}else if(scheme!=null){
						rsinfo.filter.addDataScheme(scheme);
					}else if(mimeType!=null){
						try {
							rsinfo.filter.addDataType(mimeType);
						} catch (MalformedMimeTypeException e) {
							e.printStackTrace();
						}
					}
				}
				break;
			}
			}
			eventType = parser.next();
		} while (!tagName.equals(parser.getName()));
	}
	
	private static String getName(String nameOrig, String pkgName) {
		if (nameOrig == null) {
			return null;
		}
		StringBuilder sb = null;
		if (nameOrig.startsWith(".")) {
			sb = new StringBuilder();
			sb.append(pkgName);
			sb.append(nameOrig);
		} else if (!nameOrig.contains(".")) {
			sb = new StringBuilder();
			sb.append(pkgName);
			sb.append('.');
			sb.append(nameOrig);
		} else {
			return nameOrig;
		}
		return sb.toString();
	}
}
