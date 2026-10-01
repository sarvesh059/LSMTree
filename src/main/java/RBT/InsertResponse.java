package RBT;

public class InsertResponse<K,V> {
    private Node<K,V> node;
    private V value;

    public InsertResponse(Node<K, V> node, V value) {
        this.node = node;
        this.value = value;
    }

    public Node<K, V> getNode() {
        return node;
    }

    public void setNode(Node<K, V> node) {
        this.node = node;
    }

    public V getValue() {
        return value;
    }

    public void setValue(V value) {
        this.value = value;
    }
}
