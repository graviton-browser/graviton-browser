#define GRAVITON_VERSION GetEnv("GRAVITON_VERSION")

;This file will be executed next to the application bundle image
;I.e. current directory will contain folder GravitonBrowser with application files
[Setup]
AppId={{app.graviton.browser}}
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
Source: "GravitonBrowser\app\bootstrap-console.exe"; DestDir: "{app}"; DestName: "graviton.exe"; Flags: ignoreversion

[Icons]
Name: "{commonprograms}\Graviton Browser"; Filename: "{app}\GravitonBrowser.exe"; IconFilename: "{app}\{#GRAVITON_VERSION}\GravitonBrowser.ico"; Check: returnTrue()
Name: "{commondesktop}\Graviton Browser"; Filename: "{app}\GravitonBrowser.exe";  IconFilename: "{app}\{#GRAVITON_VERSION}\GravitonBrowser.ico"; Check: returnFalse()


[Run]
Filename: "{app}\GravitonBrowser.exe"; Parameters: "-Xappcds:generatecache";
Filename: "{app}\GravitonBrowser.exe"; Description: "{cm:LaunchProgram,GravitonBrowser}"; Parameters: "-from-installer"; Flags: nowait postinstall skipifsilent
; Filename: "{app}\GravitonBrowser.exe"; Parameters: "-install -svcName ""GravitonBrowser"" -svcDesc ""GravitonBrowser"" -mainExe ""GravitonBrowser.exe""  "; Check: returnFalse()
Filename: "{app}\GravitonBrowser.exe"; Flags: nowait postinstall skipifsilent

[UninstallRun]
; Filename: "{app}\GravitonBrowser.exe "; Parameters: "-uninstall -svcName GravitonBrowser -stopOnUninstall"; Check: returnFalse()
Filename: "{app}\GravitonBrowser.exe"; Parameters: "--uninstall"

[UninstallDelete]
Type: files; Name: "{app}\last-run-version"
Type: files; Name: "{app}\task-scheduler-error-log.txt"
Type: filesandordirs; Name: "{app}\*"

[Code]
function returnTrue(): Boolean;
begin
  Result := True;
end;

function returnFalse(): Boolean;
begin
  Result := False;
end;

const
  BN_CLICKED = 0;
  WM_COMMAND = $0111;
  CN_BASE = $BC00;
  CN_COMMAND = CN_BASE + WM_COMMAND;

{ This code eliminates the startup screen InnoSetup would otherwise make the user click through. }
{ Yay Object Pascal! It's been too long, my old love.... }

{ InnoSetup doesn't make this easy because the author believes that if the user doesn't have to click }
{ an "Install" button, this makes them more vulnerable to malware. This seems false to me because the }
{ user already had to click run, either in their Explorer or more likely their web browser. Adding another }
{ button that amounts to "did you really mean it" doesn't seem able to increase security in any meaningful }
{ way or at all - this idea feels a bit like a tiger protecting rock. So we skip it and bank the smoother }
{ install experience. }
procedure CurPageChanged(CurPageID: Integer);
var
  Param: Longint;
begin
  { if we are on the ready page, then... }
  if CurPageID = wpReady then
  begin
    { the result of this is 0, just to be precise... }
    Param := 0 or BN_CLICKED shl 16;
    { post the click notification message to the next button }
    PostMessage(WizardForm.NextButton.Handle, CN_COMMAND, Param, 0);
  end;
end;