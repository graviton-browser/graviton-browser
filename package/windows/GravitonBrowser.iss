#define GRAVITON_VERSION GetEnv("GRAVITON_VERSION")

;This file will be executed next to the application bundle image
;I.e. current directory will contain folder GravitonBrowser with application files
[Setup]
AppId={{net.plan99.graviton.browser}}
AppName=Graviton Browser
AppVersion={#GRAVITON_VERSION}
AppVerName=Graviton Browser {#GRAVITON_VERSION}
AppPublisher=Graviton Team
AppComments=Graviton Browser
AppCopyright=Copyright (C) 2018
DefaultDirName={localappdata}\GravitonBrowser
DisableStartupPrompt=Yes
DisableDirPage=Yes
DisableProgramGroupPage=Yes
DisableReadyPage=Yes
DisableFinishedPage=Yes
DisableWelcomePage=Yes
DefaultGroupName=Graviton Browser
;Optional License
LicenseFile=
; Require Windows 8+ - no particular reason for this but I don't want to test on older versions.
MinVersion=0,6.2.9200
OutputBaseFilename=GravitonBrowser
Compression=lzma
SolidCompression=yes
PrivilegesRequired=lowest
SetupIconFile=GravitonBrowser\GravitonBrowser.ico
UninstallDisplayIcon={app}\{#GRAVITON_VERSION}\GravitonBrowser.ico
UninstallDisplayName=Graviton Browser
WizardImageStretch=No
WizardSmallImageFile=GravitonBrowser-setup-icon.bmp   
ArchitecturesInstallIn64BitMode=x64


[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "GravitonBrowser\GravitonBrowser.exe"; DestDir: "{app}\{#GRAVITON_VERSION}"; Flags: ignoreversion
Source: "GravitonBrowser\*"; DestDir: "{app}\{#GRAVITON_VERSION}"; Flags: ignoreversion recursesubdirs createallsubdirs; Excludes: "bootstrap.exe"
Source: "GravitonBrowser\app\bootstrap.exe"; DestDir: "{app}"; DestName: "GravitonBrowser.exe"; Flags: ignoreversion

[Icons]
Name: "{group}\Graviton Browser"; Filename: "{app}\GravitonBrowser.exe"; IconFilename: "{app}\GravitonBrowser.ico"; Check: returnTrue()
Name: "{commondesktop}\Graviton Browser"; Filename: "{app}\GravitonBrowser.exe";  IconFilename: "{app}\GravitonBrowser.ico"; Check: returnFalse()


[Run]
Filename: "{app}\GravitonBrowser.exe"; Parameters: "-Xappcds:generatecache"; Check: returnFalse()
Filename: "{app}\GravitonBrowser.exe"; Description: "{cm:LaunchProgram,GravitonBrowser}"; Parameters: "-from-installer"; Flags: nowait postinstall skipifsilent; Check: returnTrue()
Filename: "{app}\GravitonBrowser.exe"; Parameters: "-install -svcName ""GravitonBrowser"" -svcDesc ""GravitonBrowser"" -mainExe ""GravitonBrowser.exe""  "; Check: returnFalse()

[UninstallRun]
Filename: "{app}\GravitonBrowser.exe "; Parameters: "-uninstall -svcName GravitonBrowser -stopOnUninstall"; Check: returnFalse()

[Code]
function returnTrue(): Boolean;
begin
  Result := True;
end;

function returnFalse(): Boolean;
begin
  Result := False;
end;

function InitializeSetup(): Boolean;
begin
// Possible future improvements:
//   if version less or same => just launch app
//   if upgrade => check if same app is running and wait for it to exit
//   Add pack200/unpack200 support? 
  Result := True;
end;  
