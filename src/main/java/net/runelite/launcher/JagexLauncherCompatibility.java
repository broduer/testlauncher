/*
 * Copyright (c) 2024, YvesW <https://github.com/YvesW>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import static net.runelite.launcher.Launcher.isProcessElevated;
import static net.runelite.launcher.Launcher.nativesLoaded;
import static net.runelite.launcher.Launcher.regDeleteValue;

@Slf4j
class JagexLauncherCompatibility
{
	// this is set to RUNASADMIN
	private static final String COMPAT_KEY = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\AppCompatFlags\\Layers";

	static boolean check() {
		if (!nativesLoaded) {
			log.debug("Launcher natives were not loaded. Skipping Jagex launcher compatibility check.");
			return false;
		}

		try {
			Process currentProcess = Runtime.getRuntime().exec(System.getenv("windir") +"\\system32\\"+"tasklist.exe /fo csv /nh");
			BufferedReader reader = new BufferedReader(new InputStreamReader(currentProcess.getInputStream()));
			String line;
			String currentProcessName = null;
			while ((line = reader.readLine()) != null) {
				String[] processInfo = line.split(",");
				currentProcessName = processInfo[0].replace("\"", "");
				if (!currentProcessName.equals("svchost.exe")) {
					break;
				}
			}

			Process parentProcess = Runtime.getRuntime().exec(System.getenv("windir") +"\\system32\\"+"tasklist.exe /fo csv /fi \"pid eq "+currentProcessName+"\" /nh");
			reader = new BufferedReader(new InputStreamReader(parentProcess.getInputStream()));
			String parentProcessName = null;
			while ((line = reader.readLine()) != null) {
				String[] processInfo = line.split(",");
				parentProcessName = processInfo[0].replace("\"", "");
			}

			// The only problematic configuration is for us to be running as admin & the Jagex launcher to *not* be running as admin
			if (parentProcessName == null || !processIsJagexLauncher(parentProcessName) || !isProcessElevated(currentProcessName) || isProcessElevated(parentProcessName)) {
				return false;
			}

			log.error("OpenRune is running with elevated permissions, but the Jagex launcher is not. Privileged processes " +
					"can't have environment variables passed to them from unprivileged processes. This will cause you to be " +
					"unable to login. Either run OpenRune as a regular user, or run the Jagex launcher as an administrator.");

			// attempt to fix this by removing the compatibility settings
			boolean regEdited = regDeleteValue("HKLM", COMPAT_KEY, currentProcessName); // all users
			regEdited |= regDeleteValue("HKCU", COMPAT_KEY, currentProcessName); // current user

			if (regEdited) {
				log.info("Application compatibility settings have been unset for {}", currentProcessName);
			}

			showErrorDialog(regEdited,currentProcessName);
			return true;
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}


	private static boolean isProcessElevated(String processName) {
		try {
			Process process = Runtime.getRuntime().exec("whoami /groups");
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.contains("S-1-16-12288")) { // SID for elevated permissions
					return true;
				}
			}
		} catch (IOException e) {
			e.printStackTrace(); // Handle the exception as needed
		}
		return false;
	}

	private static boolean processIsJagexLauncher(String processName) {
		try {
			Process process = Runtime.getRuntime().exec(System.getenv("windir") + "\\system32\\tasklist.exe /fo csv /nh");
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				String name = parts[0].replaceAll("\"", "");
				if (name.equalsIgnoreCase(processName)) {
					return true;
				}
			}
		} catch (IOException e) {
			e.printStackTrace(); // Handle the exception as needed
		}
		return false;
	}

	private static boolean processIsJagexLauncher(ProcessBuilder processBuilder) {
		try {
			processBuilder.redirectInput(ProcessBuilder.Redirect.PIPE);
			Process process = processBuilder.start();
			process.waitFor(); // Wait for the process to finish
			List<String> command = processBuilder.command();
			if (command != null && !command.isEmpty()) {
				String cmd = command.get(0); // Assuming first element is the command
				return "JagexLauncher.exe".equals(pathFilename(cmd));
			}
		} catch (IOException | InterruptedException e) {
			e.printStackTrace(); // Handle the exception as needed
		}
		return false;
	}


	private static String pathFilename(String path)
	{
		Path p = Paths.get(path);
		return p.getFileName().toString();
	}

	private static void showErrorDialog(boolean patched, String command) {
		StringBuilder sb = new StringBuilder();
		sb.append("Running OpenRune as an administrator is incompatible with the Jagex launcher.");
		if (patched) {
			sb.append(" OpenRune has attempted to fix this problem by changing the compatibility settings of ").append(command).append('.');
			sb.append(" Try running OpenRune again.");
		}
		sb.append(" If the problem persists, either run the Jagex launcher as administrator, or change the ")
				.append(command).append(" compatibility settings to not run as administrator.");

		final String message = sb.toString();
		SwingUtilities.invokeLater(() ->
				new FatalErrorDialog(message)
						.open());
	}
}
