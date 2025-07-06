# BitTorrent - Protótipo Funcional

Este projeto é um protótipo funcional de um cliente BitTorrent, desenvolvido para a disciplina de Redes de Computadores. Ele implementa um protocolo P2P (peer-to-peer) para a transferência de arquivos, utilizando comunicação TCP entre os Peers e comunicação UDP entre os Peers e o Tracker.

## Pré-requisitos

Antes de começar, garanta que você tenha os seguintes softwares instalados:
* **Java Development Kit (JDK)**: Versão 17 ou superior.
* **IDE Java (Opcional):** IntelliJ IDEA, Eclipse ou VS Code para facilitar a execução.

## Como Executar

### 1. Iniciar o Tracker

O Tracker é o servidor central que gerencia os Peers. Ele deve ser o primeiro a ser executado.

-   Navegue e execute a classe `TrackerMain`:
    ```bash
    /home/andre/Documents/udesc/REDES/bit-torrent-functional-prototype/src/main/java/org/bittorrent/tracker/TrackerMain.java
    ```
-   Por padrão, o Tracker será iniciado na porta `8000`.

### 2. Iniciar um ou mais Peers

Cada Peer é um cliente que pode compartilhar e baixar arquivos. Você pode executar vários Peers simultaneamente em portas diferentes.

-   Navegue e execute a classe `PeerMain`:
    ```bash
    /home/andre/Documents/udesc/REDES/bit-torrent-functional-prototype/src/main/java/org/bittorrent/peer/PeerMain.java
    ```
-   Ao executar, o console solicitará duas informações:
    1.  **IP do Tracker:** Insira o endereço IP da máquina onde o Tracker está sendo executado.
    2.  **Porta do Peer:** Insira uma porta para este Peer operar (ex: `9001`, `9002`, etc.). Cada Peer deve ter uma porta única.

-   Repita este passo para cada Peer que desejar adicionar à rede.

### 3. Adicionar Arquivos para Compartilhamento

-   Após iniciar um Peer, uma nova pasta será criada automaticamente dentro do diretório `peerFiles` (localizado na raiz do projeto).
-   O nome da pasta corresponderá ao endereço do Peer.
-   **Copie os arquivos que você deseja compartilhar para dentro desta pasta.** O Peer irá automaticamente anunciá-los para o Tracker.