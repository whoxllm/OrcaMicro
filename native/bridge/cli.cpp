// SPDX-License-Identifier: AGPL-3.0-or-later
// Desktop driver for the engine: runs one slice request, the same JSON the app sends.
//
//   orcamicro_cli request.json     prints the engine's response; exit status 0 when it sliced
//   orcamicro_cli --version

#include "engine.hpp"

#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

int main(int argc, char **argv)
{
    if (argc != 2) {
        std::cerr << "usage: orcamicro_cli request.json | --version\n";
        return 2;
    }
    if (std::string(argv[1]) == "--version") {
        std::cout << orcamicro::version() << "\n";
        return 0;
    }
    std::ifstream in(argv[1]);
    if (!in) {
        std::cerr << "cannot read " << argv[1] << "\n";
        return 2;
    }
    std::stringstream request;
    request << in.rdbuf();
    const std::string response = orcamicro::slice(request.str());
    std::cout << response << std::endl;
    return response.find("\"ok\":true") != std::string::npos ? 0 : 1;
}
