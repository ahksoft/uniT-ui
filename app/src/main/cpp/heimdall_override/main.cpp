/* Heimdall CLI entrypoint adapted for Adbify's Android USB descriptor bridge. */
#include <algorithm>
#include <map>
#include <string>

#include "libpit.h"
#include "Heimdall.h"
#include "BridgeManager.h"
#include "HelpAction.h"
#include "Interface.h"

using namespace std;
using namespace Heimdall;

extern "C" int unitool_heimdall_run(
    int argc, char **argv, int androidUsbFileDescriptor, std::string *output)
{
    BridgeManager::SetAndroidUsbFileDescriptor(androidUsbFileDescriptor);
    Interface::SetOutputCapture(output);
    Interface::SetStdoutErrors(false);

    int result = 0;
    if (argc < 2)
    {
        Interface::PrintUsage();
    }
    else
    {
        const map<string, Interface::ActionInfo>::const_iterator actionIt =
            Interface::GetActionMap().find(argv[1]);

        if (actionIt != Interface::GetActionMap().end())
            result = actionIt->second.executeFunction(argc, argv);
        else
            result = HelpAction::Execute(argc, argv);
    }

    Interface::SetOutputCapture(nullptr);
    BridgeManager::SetAndroidUsbFileDescriptor(-1);
    return result;
}

int main(int argc, char **argv)
{
    return unitool_heimdall_run(argc, argv, -1, nullptr);
}
